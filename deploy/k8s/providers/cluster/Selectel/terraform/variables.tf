# --- Credentials (exported by the Makefile from deploy/k8s/config/.env) ---

variable "account_id" {
  description = "Selectel account number, Keystone domain_name (SELECTEL_ACCOUNT_ID)."
  type        = string
}

variable "iam_username" {
  description = "Selectel service user name (SELECTEL_IAM_USER)."
  type        = string
}

variable "iam_password" {
  description = "Selectel service user password (SELECTEL_IAM_PASSWORD)."
  type        = string
  sensitive   = true
}

variable "project_id" {
  description = "VPC project id the cluster is created in (SELECTEL_PROJECT_ID, 32 hex chars)."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.project_id))
    error_message = "project_id must be a 32-character hex id; run 'make project-id' to resolve it from the project name."
  }
}

variable "auth_url" {
  description = "Keystone endpoint of the Selectel cloud platform."
  type        = string
  default     = "https://cloud.api.selcloud.ru/identity/v3"
}

# --- Stand shape (AGENT-98 comment: ru-3, BASIC, 4 vCPU / 8 GB, 140 GB fast, 6 or 12 nodes) ---

variable "cluster_name" {
  description = "MKS cluster name."
  type        = string
  default     = "riid-bench"
}

variable "region" {
  description = "Selectel region."
  type        = string
  default     = "ru-3"
}

variable "availability_zone" {
  description = "Availability zone the node group is pinned to; must belong to var.region."
  type        = string
  # ru-3b, not ru-3a: ru-3a had no capacity for these nodes and every node group
  # created there was rolled back by Selectel within a minute.
  default = "ru-3b"

  validation {
    condition     = startswith(var.availability_zone, var.region)
    error_message = "availability_zone must start with the region name, e.g. ru-3a for region ru-3."
  }
}

variable "cluster_type" {
  description = "BASIC = single non-redundant master, which is what the bench stand uses."
  type        = string
  default     = "BASIC"
}

variable "kube_version" {
  description = "Pinned Kubernetes version; null takes the region default (see kube_versions data source)."
  type        = string
  default     = null
}

variable "nodes_count" {
  description = "Node group size: 6 for the preparation stand, 12 for the production run."
  type        = number
  default     = 6

  validation {
    condition     = var.nodes_count >= 1 && var.nodes_count <= 100
    error_message = "nodes_count must be between 1 and 100."
  }
}

variable "flavor_id" {
  description = "Fixed Selectel flavor, e.g. 1022 for SL1.4-8192 (4 vCPU / 8 GB). When set it wins over cpus/ram_mb."
  type        = string
  default     = null
}

variable "cpus" {
  description = "vCPU per node."
  type        = number
  default     = 4
}

variable "ram_mb" {
  description = "RAM per node, MiB."
  type        = number
  default     = 8192
}

variable "volume_gb" {
  description = "Boot disk per node, GiB."
  type        = number
  default     = 140
}

variable "volume_type_family" {
  description = "Network disk family; 'fast' is the SSD tier rated 25000/15000 IOPS and 500 MB/s."
  type        = string
  default     = "fast"
}

variable "enable_autorepair" {
  description = "Node autorepair. Off by default: a node replaced mid-run invalidates the measurement."
  type        = bool
  default     = false
}

variable "labels" {
  description = "Extra kubernetes labels put on every node of the group."
  type        = map(string)
  default     = {}
}
