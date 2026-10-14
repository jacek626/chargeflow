locals {
  repos = {
    charging_session_service = "${var.name_prefix}-charging-session-service"
    notification_service     = "${var.name_prefix}-notification-service"
  }

  lifecycle_policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep last ${var.image_count_to_keep} images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.image_count_to_keep
        }
        action = { type = "expire" }
      }
    ]
  })
}

resource "aws_ecr_repository" "repos" {
  for_each = local.repos

  name                 = each.value
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = { Name = each.value }
}

resource "aws_ecr_lifecycle_policy" "repos" {
  for_each = aws_ecr_repository.repos

  repository = each.value.name
  policy     = local.lifecycle_policy
}
