# SNS topic — receives all domain events published by Charging Session Service outbox poller
resource "aws_sns_topic" "domain_events" {
  name = "${var.name_prefix}-domain-events"
}

# Dead letter queue — captures messages that exceed max receive count
resource "aws_sqs_queue" "notifications_dlq" {
  name                      = "${var.name_prefix}-notifications-dlq"
  message_retention_seconds = var.message_retention_seconds
}

# Notifications queue — Notification Service polls this queue for domain events
resource "aws_sqs_queue" "notifications" {
  name                       = "${var.name_prefix}-notifications"
  visibility_timeout_seconds = var.sqs_visibility_timeout_seconds
  message_retention_seconds  = var.message_retention_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.notifications_dlq.arn
    maxReceiveCount     = var.dlq_max_receive_count
  })
}

# Allow SNS to publish messages to the SQS queue
resource "aws_sqs_queue_policy" "notifications" {
  queue_url = aws_sqs_queue.notifications.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowSNSPublish"
        Effect    = "Allow"
        Principal = { Service = "sns.amazonaws.com" }
        Action    = "sqs:SendMessage"
        Resource  = aws_sqs_queue.notifications.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = aws_sns_topic.domain_events.arn
          }
        }
      }
    ]
  })
}

# Subscribe the notifications queue to the domain events topic
resource "aws_sns_topic_subscription" "notifications" {
  topic_arn = aws_sns_topic.domain_events.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.notifications.arn

  raw_message_delivery = true
}
