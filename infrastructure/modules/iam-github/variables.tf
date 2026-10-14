variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

variable "github_repo" {
  type        = string
  description = "GitHub repository in org/repo format (e.g. acme/chargeflow)"
}
