# Prefiks nazw zasobów, np. "chargeflow-dev" — rozróżnia środowiska na jednym koncie.
variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

# Nazwa środowiska (dev/prod...).
variable "environment" {
  type        = string
  description = "Deployment environment"
}

# Visibility timeout: po odebraniu wiadomość jest niewidoczna przez tyle sekund. Jeśli konsument jej
# nie usunie w tym czasie (błąd/timeout), wiadomość wraca do kolejki i liczy się kolejna próba.
# Musi być dłuższy niż typowy czas przetwarzania (zapis DynamoDB + wysyłka SES).
variable "sqs_visibility_timeout_seconds" {
  type        = number
  description = "SQS visibility timeout in seconds"
  default     = 30
}

# Po 3 nieudanych odbiorach wiadomość trafia do DLQ (przy timeoucie 30 s to ok. 90 s prób).
variable "dlq_max_receive_count" {
  type        = number
  description = "Max receive count before a message moves to the DLQ"
  default     = 3
}

# 86400 s = 1 dzień (SQS pozwala od 60 s do 14 dni). Używane dla kolejki głównej i DLQ.
# UWAGA: DLQ zwykle ma dłuższą retencję (np. 14 dni), by zdążyć zbadać błędne wiadomości.
variable "message_retention_seconds" {
  type        = number
  description = "SQS message retention period in seconds"
  default     = 86400
}
