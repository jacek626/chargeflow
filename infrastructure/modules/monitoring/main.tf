# CloudWatch log group — "folder" na logi Charging Session Service.
# UWAGA: ta grupa jest nieużywana — ECS pisze do własnych grup ".../charging-session-service/ecs"
# zdefiniowanych w module ecs (z retencją na sztywno 30 dni), więc ta pozostaje pusta.
resource "aws_cloudwatch_log_group" "charging_session_service" {
  name              = "/chargeflow/${var.environment}/charging-session-service" # konwencja ścieżki: projekt/środowisko/serwis
  retention_in_days = var.log_retention_days                                    # po tylu dniach CloudWatch usuwa logi (bez tego trzyma je wiecznie i płacisz)

  tags = {
    Environment = var.environment
    Service     = "charging-session-service"
  }
}

# CloudWatch log group — "folder" na logi Notification Service.
# UWAGA: również nieużywana — ECS loguje do ".../notification-service/ecs" z modułu ecs.
resource "aws_cloudwatch_log_group" "notification_service" {
  name              = "/chargeflow/${var.environment}/notification-service"
  retention_in_days = var.log_retention_days # po tylu dniach logi są automatycznie usuwane

  tags = {
    Environment = var.environment
    Service     = "notification-service"
  }
}
