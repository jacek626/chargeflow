# Outputy modułu alb — używane przez inne moduły i wystawiane w environments/dev/outputs.tf.
# ARN ALB — unikalny identyfikator zasobu (np. do alarmów CloudWatch).
output "alb_arn" {
  description = "ALB ARN"
  value       = aws_lb.main.arn
}

# Publiczna nazwa DNS ALB (xxx.eu-central-1.elb.amazonaws.com) — pod nią dostępne jest API.
output "alb_dns_name" {
  description = "ALB DNS name"
  value       = aws_lb.main.dns_name
}

# Hosted zone ID ALB — potrzebne do rekordu alias w Route 53 (własna domena -> ALB).
output "alb_zone_id" {
  description = "ALB Route 53 zone ID (for alias records)"
  value       = aws_lb.main.zone_id
}

# ARN target group — przekazywany do modułu ecs, żeby serwis rejestrował w niej swoje taski.
output "charging_session_target_group_arn" {
  description = "Target group ARN for charging-session-service"
  value       = aws_lb_target_group.charging_session.arn
}
