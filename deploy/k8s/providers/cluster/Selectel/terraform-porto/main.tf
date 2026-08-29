# Self-managed kubeadm stand on Ubuntu 22.04 for the Porto arm (AGENT-108).
# Selectel MKS gives no way to choose the node OS and lands on Ubuntu 24.04,
# while Porto is packaged for focal and jammy only. Everything that lives inside
# the Kubernetes API stays with bootstrap/ and helm, exactly as for the MKS stand.

data "openstack_images_image_v2" "node" {
  name        = var.image_name
  most_recent = true
  visibility  = "public"
}

data "openstack_compute_flavor_v2" "worker" {
  name = var.flavor_name
}

data "openstack_compute_flavor_v2" "control_plane" {
  name = var.control_plane_flavor_name
}

data "openstack_networking_network_v2" "external" {
  name     = "external-network"
  external = true
}

locals {
  volume_type = "${var.volume_type_family}.${var.availability_zone}"

  # Fixed, so both cloud-init templates know the API endpoint before anything
  # is created: workers must be able to join without a data lookup.
  control_plane_ip = cidrhost(var.subnet_cidr, 10)

  kubeadm_token = "${random_string.token_id.result}.${random_string.token_secret.result}"

  node_labels = join(",", [for k, v in var.labels : "${k}=${v}"])

  # Общая часть подготовки ноды рендерится один раз и вкладывается в оба
  # cloud-init, чтобы containerd и kubeadm ставились ровно одинаково.
  common_node_script = templatefile("${path.module}/cloud-init/common-node.sh.tftpl", {
    kube_series = var.kube_series
  })
}

resource "random_string" "token_id" {
  length  = 6
  upper   = false
  special = false
}

resource "random_string" "token_secret" {
  length  = 16
  upper   = false
  special = false
}

# The stand is created and destroyed by one operator from one workstation, so the
# key is generated here instead of asking for one. `make ssh-key` writes it out.
resource "tls_private_key" "stand" {
  algorithm = "ED25519"
}

resource "openstack_compute_keypair_v2" "stand" {
  name       = "${var.cluster_name}-key"
  public_key = tls_private_key.stand.public_key_openssh
}

# --- Network ---

resource "openstack_networking_network_v2" "stand" {
  name           = "${var.cluster_name}-net"
  admin_state_up = true
}

resource "openstack_networking_subnet_v2" "stand" {
  name            = "${var.cluster_name}-subnet"
  network_id      = openstack_networking_network_v2.stand.id
  cidr            = var.subnet_cidr
  ip_version      = 4
  dns_nameservers = var.dns_nameservers
}

# Gateway to the outside: SNAT through the router is what lets the workers reach
# apt and the Porto release on GitHub without a floating ip each.
resource "openstack_networking_router_v2" "stand" {
  name                = "${var.cluster_name}-router"
  admin_state_up      = true
  external_network_id = data.openstack_networking_network_v2.external.id
}

resource "openstack_networking_router_interface_v2" "stand" {
  router_id = openstack_networking_router_v2.stand.id
  subnet_id = openstack_networking_subnet_v2.stand.id
}

resource "openstack_networking_secgroup_v2" "stand" {
  name        = "${var.cluster_name}-sg"
  description = "RIID Porto stand: ssh and kube-api from outside, everything inside the subnet"
}

resource "openstack_networking_secgroup_rule_v2" "ssh" {
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "tcp"
  port_range_min    = 22
  port_range_max    = 22
  remote_ip_prefix  = var.ssh_allowed_cidr
  security_group_id = openstack_networking_secgroup_v2.stand.id
}

resource "openstack_networking_secgroup_rule_v2" "kube_api" {
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "tcp"
  port_range_min    = 6443
  port_range_max    = 6443
  remote_ip_prefix  = var.ssh_allowed_cidr
  security_group_id = openstack_networking_secgroup_v2.stand.id
}

# Node to node: kubelet, etcd, flannel vxlan, NodePorts, the local registry and
# the dfdaemon peer traffic all live here. Listing them one by one buys nothing
# on a stand whose only ingress is the two rules above.
resource "openstack_networking_secgroup_rule_v2" "intra_tcp" {
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "tcp"
  port_range_min    = 1
  port_range_max    = 65535
  remote_ip_prefix  = var.subnet_cidr
  security_group_id = openstack_networking_secgroup_v2.stand.id
}

resource "openstack_networking_secgroup_rule_v2" "intra_udp" {
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "udp"
  port_range_min    = 1
  port_range_max    = 65535
  remote_ip_prefix  = var.subnet_cidr
  security_group_id = openstack_networking_secgroup_v2.stand.id
}

resource "openstack_networking_secgroup_rule_v2" "intra_icmp" {
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "icmp"
  remote_ip_prefix  = var.subnet_cidr
  security_group_id = openstack_networking_secgroup_v2.stand.id
}

# Pod to pod inside the flannel overlay arrives with the pod address, not the
# node one, so the pod network needs its own rule.
resource "openstack_networking_secgroup_rule_v2" "pods" {
  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = "tcp"
  port_range_min    = 1
  port_range_max    = 65535
  remote_ip_prefix  = var.pod_cidr
  security_group_id = openstack_networking_secgroup_v2.stand.id
}

# --- Control plane ---

resource "openstack_networking_port_v2" "control_plane" {
  name               = "${var.cluster_name}-cp-port"
  network_id         = openstack_networking_network_v2.stand.id
  admin_state_up     = true
  security_group_ids = [openstack_networking_secgroup_v2.stand.id]

  fixed_ip {
    subnet_id  = openstack_networking_subnet_v2.stand.id
    ip_address = local.control_plane_ip
  }

  depends_on = [openstack_networking_subnet_v2.stand]
}

resource "openstack_networking_floatingip_v2" "control_plane" {
  pool = data.openstack_networking_network_v2.external.name
}

resource "openstack_networking_floatingip_associate_v2" "control_plane" {
  floating_ip = openstack_networking_floatingip_v2.control_plane.address
  port_id     = openstack_networking_port_v2.control_plane.id
}

resource "openstack_compute_instance_v2" "control_plane" {
  name              = "${var.cluster_name}-cp"
  flavor_id         = data.openstack_compute_flavor_v2.control_plane.id
  key_pair          = openstack_compute_keypair_v2.stand.name
  availability_zone = var.availability_zone

  block_device {
    uuid                  = data.openstack_images_image_v2.node.id
    source_type           = "image"
    destination_type      = "volume"
    volume_size           = var.control_plane_volume_gb
    volume_type           = local.volume_type
    boot_index            = 0
    delete_on_termination = true
  }

  network {
    port = openstack_networking_port_v2.control_plane.id
  }

  user_data = templatefile("${path.module}/cloud-init/control-plane.yaml.tftpl", {
    common         = local.common_node_script
    ssh_public_key = trimspace(tls_private_key.stand.public_key_openssh)
    api_advertise  = local.control_plane_ip
    api_extra_san  = openstack_networking_floatingip_v2.control_plane.address
    pod_cidr       = var.pod_cidr
    kubeadm_token  = local.kubeadm_token
    token_ttl      = var.kubeadm_token_ttl
  })

  lifecycle {
    # user_data is only read on first boot; re-rendering it must not silently
    # recreate a running stand in the middle of a measurement.
    ignore_changes = [user_data]
  }

  depends_on = [openstack_networking_router_interface_v2.stand]
}

# --- Workers ---

resource "openstack_compute_instance_v2" "worker" {
  count = var.nodes_count

  name              = format("%s-w%02d", var.cluster_name, count.index + 1)
  flavor_id         = data.openstack_compute_flavor_v2.worker.id
  key_pair          = openstack_compute_keypair_v2.stand.name
  availability_zone = var.availability_zone
  security_groups   = [openstack_networking_secgroup_v2.stand.name]

  block_device {
    uuid                  = data.openstack_images_image_v2.node.id
    source_type           = "image"
    destination_type      = "volume"
    volume_size           = var.volume_gb
    volume_type           = local.volume_type
    boot_index            = 0
    delete_on_termination = true
  }

  network {
    uuid = openstack_networking_network_v2.stand.id
  }

  user_data = templatefile("${path.module}/cloud-init/worker.yaml.tftpl", {
    common         = local.common_node_script
    ssh_public_key = trimspace(tls_private_key.stand.public_key_openssh)
    api_endpoint   = "${local.control_plane_ip}:6443"
    api_host       = local.control_plane_ip
    api_port       = 6443
    kubeadm_token  = local.kubeadm_token
    porto_version  = var.porto_version
    porto_insecure = var.porto_insecure_registries
    node_labels    = local.node_labels
  })

  lifecycle {
    ignore_changes = [user_data]
  }

  depends_on = [
    openstack_networking_router_interface_v2.stand,
    openstack_compute_instance_v2.control_plane,
  ]
}
