# Region, w którym powstanie bucket stanu i tabela locków.
# Musi zgadzać się z region= podawanym przy terraform init backendu w dev.
variable "aws_region" {
  type    = string
  default = "eu-central-1" # Frankfurt - najbliższy region dla Polski, dane w UE
}

# Nazwa bucketu stanu - brak default, więc trzeba ją podać (-var, terraform.tfvars albo prompt).
# Nazwy bucketów S3 są globalne dla wszystkich kont AWS na świecie.
variable "state_bucket_name" {
  type        = string
  description = "Globally-unique S3 bucket name for Terraform state (e.g. chargeflow-tfstate-<account-id>)"
}

# Nazwa tabeli DynamoDB na locki stanu; default pasuje do przykładu z environments/dev/backend.tf
variable "lock_table_name" {
  type        = string
  description = "DynamoDB table name for Terraform state locking"
  default     = "chargeflow-tfstate-lock"
}
