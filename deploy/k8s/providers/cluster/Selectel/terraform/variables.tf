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

# --- Stand shape (AGENT-98 comment: ru-3, BASIC, 4 vCPU / 8 GB, 100 GB fast, 6 or 12 nodes) ---

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
  description = "Boot disk per worker node, GiB."
  type        = number
  # Not the 11.8 GiB of compressed egress per pull: a worker holds three copies at
  # once - RIID's cache, containerd unpacked, dfdaemon pieces. A full noprefix arm
  # measured 66.2 GiB of them on 2026-09-11, and kubelet keeps another 15% free,
  # so 50 GiB died mid-arm and 80 GiB finished with 1 GiB to spare.
  default = 100
}

# scheduler/manager keep their state in PVCs (mysql/redis/manager have no local
# data at all); registry's dataset and monitoring's VictoriaMetrics+Grafana
# emptyDir are the only infra roles that actually write to the node's own disk,
# and even registry's dataset lives in its own 30Gi PVC, not the boot disk.
variable "infra_volume_gb" {
  description = "Boot disk per infra node, GiB, keyed by role (monitoring/registry/scheduler/manager). Only used when dedicated_infra_nodes is true."
  type        = map(number)
  default = {
    monitoring = 40
    registry   = 40
    scheduler  = 20
    manager    = 30
  }

  validation {
    condition     = alltrue([for k in ["monitoring", "registry", "scheduler", "manager"] : contains(keys(var.infra_volume_gb), k)])
    error_message = "infra_volume_gb must set all four infra roles: monitoring, registry, scheduler, manager."
  }
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

# The bench needs the registry and the metrics stack off the measured nodes, and on
# MKS only a node-group taint survives - the control plane strips a kubectl one.
variable "dedicated_infra_nodes" {
  description = "Carve tainted monitoring, registry, scheduler and manager nodes out of nodes_count."
  type        = bool
  default     = true

  validation {
    condition     = !var.dedicated_infra_nodes || var.nodes_count >= 5
    error_message = "dedicated_infra_nodes takes 4 nodes out of nodes_count, so nodes_count must be at least 5."
  }
}
