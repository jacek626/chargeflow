output "alb_arn" {
  description = "ALB ARN"
  value       = aws_lb.main.arn
}

output "alb_dns_name" {
  description = "ALB DNS name"
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "ALB Route 53 zone ID (for alias records)"
  value       = aws_lb.main.zone_id
}

output "charging_session_target_group_arn" {
  description = "Target group ARN for charging-session-service"
  value       = aws_lb_target_group.charging_session.arn
}
