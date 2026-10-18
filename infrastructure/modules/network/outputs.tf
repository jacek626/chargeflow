# Outputy modułu network — wartości przekazywane do innych modułów w environments/dev/main.tf.
# ID VPC — potrzebne np. modułowi alb (target group musi wiedzieć, w jakiej VPC są cele).
output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

# Lista ID subnetów publicznych — trafia do ALB.
output "public_subnet_ids" {
  description = "Public subnet IDs"
  value       = aws_subnet.public[*].id # splat [*] = lista id ze wszystkich instancji utworzonych przez count
}

# Lista ID subnetów prywatnych — trafia do ECS (taski) i RDS (subnet group).
output "private_subnet_ids" {
  description = "Private subnet IDs"
  value       = aws_subnet.private[*].id
}

# ID SG ALB — przekazywane do modułu alb.
output "alb_sg_id" {
  description = "ALB security group ID"
  value       = aws_security_group.alb.id
}

# ID SG tasków — przekazywane do modułu ecs (network_configuration serwisów).
output "ecs_sg_id" {
  description = "ECS tasks security group ID"
  value       = aws_security_group.ecs.id
}

# ID SG bazy — przekazywane do modułu rds.
output "rds_sg_id" {
  description = "RDS security group ID"
  value       = aws_security_group.rds.id
}
