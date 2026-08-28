# Reproducible MKS stand for the RIID benchmark (AGENT-98 / AGENT-74).
# Scope is deliberately narrow: cluster, nodes, kubeconfig. Everything that lives
# inside the Kubernetes API (Dragonfly, RIID, registry) stays with bootstrap/ helm.

data "selectel_mks_kube_versions_v1" "available" {
  project_id = var.project_id
  region     = var.region
}

locals {
  kube_version = coalesce(var.kube_version, data.selectel_mks_kube_versions_v1.available.default_version)
  volume_type  = "${var.volume_type_family}.${var.availability_zone}"
}

resource "selectel_mks_cluster_v1" "bench" {
  name         = var.cluster_name
  project_id   = var.project_id
  region       = var.region
  kube_version = local.kube_version
  cluster_type = var.cluster_type

  # Off on purpose: a patch upgrade or a node repair in the middle of a run
  # changes the stand under the measurement. Selectel only allows pinning the
  # patch level when kube_version carries one, hence the length check.
  enable_patch_version_auto_upgrade = length(split(".", local.kube_version)) < 3
  enable_autorepair                 = var.enable_autorepair

  timeouts {
    create = "60m"
    delete = "60m"
  }
}

resource "selectel_mks_nodegroup_v1" "workers" {
  cluster_id        = selectel_mks_cluster_v1.bench.id
  project_id        = var.project_id
  region            = var.region
  availability_zone = var.availability_zone

  nodes_count = var.nodes_count
  volume_gb   = var.volume_gb
  volume_type = local.volume_type

  # Either a fixed flavor or an arbitrary cpus/ram_mb pair — never both.
  flavor_id = var.flavor_id
  cpus      = var.flavor_id == null ? var.cpus : null
  ram_mb    = var.flavor_id == null ? var.ram_mb : null

  install_nvidia_device_plugin = false
  labels                       = var.labels

  # 12 nodes on network disks take noticeably longer than the provider default.
  timeouts {
    create = "90m"
    update = "90m"
    delete = "60m"
  }
}

data "selectel_mks_kubeconfig_v1" "bench" {
  cluster_id = selectel_mks_cluster_v1.bench.id
  project_id = var.project_id
  region     = var.region

  depends_on = [selectel_mks_nodegroup_v1.workers]
}
