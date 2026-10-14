variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

variable "environment" {
  type        = string
  description = "Deployment environment"
}

variable "callback_urls" {
  type        = list(string)
  description = "Allowed OAuth2 redirect URIs for the Angular app"
  default     = ["http://localhost:4200/auth/callback"]
}

variable "logout_urls" {
  type        = list(string)
  description = "Allowed logout redirect URIs for the Angular app"
  default     = ["http://localhost:4200"]
}
