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
  description = "Hostname and ip of every node, across all three groups."
  value = concat(
    selectel_mks_nodegroup_v1.workers.nodes,
    flatten([for g in selectel_mks_nodegroup_v1.infra : g.nodes]),
  )
}

output "nodes_count" {
  description = "Whole stand, cross-checked by bootstrap validate-cluster-state."
  value       = var.nodes_count
}

output "worker_nodes_count" {
  description = "Bench workers only - the stand minus the monitoring and registry nodes."
  value       = local.worker_nodes_count
}

output "infra_nodes" {
  description = "Hostname of each tainted infra node by role; empty when not carved out."
  value       = { for k, g in selectel_mks_nodegroup_v1.infra : k => g.nodes[*].hostname }
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
