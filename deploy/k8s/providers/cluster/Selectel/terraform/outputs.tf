output "cluster_id" {
  description = "MKS cluster id."
  value       = selectel_mks_cluster_v1.bench.id
}

output "kube_version" {
  description = "Kubernetes version the cluster was actually created with."
  value       = selectel_mks_cluster_v1.bench.kube_version
}

output "kube_api_ip" {
  description = "Address of the cluster API server."
  value       = selectel_mks_cluster_v1.bench.kube_api_ip
}

output "nodes" {
  description = "Hostname and ip of every node in the group."
  value       = selectel_mks_nodegroup_v1.workers.nodes
}

output "nodes_count" {
  description = "Node group size, cross-checked by bootstrap validate-cluster-state."
  value       = selectel_mks_nodegroup_v1.workers.nodes_count
}

output "volume_type" {
  description = "Disk tier the nodes were created on."
  value       = selectel_mks_nodegroup_v1.workers.volume_type
}

# Written to providers/cluster/Selectel/serverConfig.yaml by `make kubeconfig`.
output "kubeconfig" {
  description = "Ready to use kubeconfig for the created cluster."
  value       = data.selectel_mks_kubeconfig_v1.bench.raw_config
  sensitive   = true
}
