output "control_plane_ip" {
  description = "Floating ip of the control plane: ssh and the Kubernetes API."
  value       = openstack_networking_floatingip_v2.control_plane.address
}

output "control_plane_private_ip" {
  description = "Address the workers join through; fixed before anything is created."
  value       = local.control_plane_ip
}

output "worker_ips" {
  description = "Private address of every worker."
  value       = [for s in openstack_compute_instance_v2.worker : s.access_ip_v4]
}

output "worker_names" {
  description = "Hostname of every worker, which is also its Kubernetes node name."
  value       = [for s in openstack_compute_instance_v2.worker : s.name]
}

output "nodes_count" {
  description = "Worker count, cross-checked by bootstrap validate-cluster-state."
  value       = var.nodes_count
}

output "image_name" {
  description = "Glance image the stand was actually built from."
  value       = data.openstack_images_image_v2.node.name
}

output "porto_version" {
  description = "Porto release installed on every worker."
  value       = var.porto_version
}

output "ssh_user" {
  description = "Account created by cloud-init on every node."
  value       = "riid"
}

# Written to .ssh/id_ed25519 by `make ssh-key`; every other target depends on it.
output "ssh_private_key" {
  description = "Private half of the keypair the stand was created with."
  value       = tls_private_key.stand.private_key_openssh
  sensitive   = true
}
