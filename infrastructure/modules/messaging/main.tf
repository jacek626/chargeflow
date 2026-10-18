# SNS topic — receives all domain events published by Charging Session Service outbox poller
# (PL) Temat SNS = "tablica ogłoszeń" w modelu pub/sub: producent publikuje raz, SNS rozsyła kopię
# do każdego subskrybenta (tu: kolejki SQS Notification Service).
resource "aws_sns_topic" "domain_events" {
  name = "${var.name_prefix}-domain-events" # standardowy (nie FIFO) temat — brak gwarancji kolejności
}

# Dead letter queue — captures messages that exceed max receive count
# (PL) DLQ = "szuflada" na wiadomości, których konsument nie zdołał przetworzyć po kilku próbach.
# UWAGA: retencja DLQ taka sama jak kolejki głównej (domyślnie 1 dzień) — za mało na analizę;
# nikt jej nie czyta i nie ma alarmu CloudWatch na liczbę wiadomości w DLQ.
resource "aws_sqs_queue" "notifications_dlq" {
  name                      = "${var.name_prefix}-notifications-dlq"
  message_retention_seconds = var.message_retention_seconds # jak długo SQS trzyma wiadomość, zanim ją skasuje
}

# Notifications queue — Notification Service polls this queue for domain events
# (PL) Kolejka SQS = "skrzynka" buforująca zdarzenia; serwis odpytuje ją (long polling) we własnym tempie.
resource "aws_sqs_queue" "notifications" {
  name                       = "${var.name_prefix}-notifications"
  visibility_timeout_seconds = var.sqs_visibility_timeout_seconds # ile sekund odebrana wiadomość jest ukryta przed innymi konsumentami
  message_retention_seconds  = var.message_retention_seconds      # nieprzetworzona wiadomość znika po tym czasie

  # Redrive policy: po maxReceiveCount nieudanych odbiorach (bez DeleteMessage) SQS przenosi wiadomość do DLQ,
  # zamiast ponawiać ją w nieskończoność ("poison message").
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.notifications_dlq.arn # dokąd przenieść "trujące" wiadomości
    maxReceiveCount     = var.dlq_max_receive_count           # liczba prób przed przeniesieniem (domyślnie 3)
  })
}

# Allow SNS to publish messages to the SQS queue
# (PL) Resource-based policy na kolejce: bez niej SNS nie ma prawa wrzucać wiadomości do SQS.
resource "aws_sqs_queue_policy" "notifications" {
  queue_url = aws_sqs_queue.notifications.id # dla aws_sqs_queue atrybut id to URL kolejki

  # Dokument polityki IAM w JSON (jsonencode zamienia obiekt HCL na string JSON).
  policy = jsonencode({
    Version = "2012-10-17" # stała wersja języka polityk IAM (nie data utworzenia)
    Statement = [
      {
        # Pozwól usłudze SNS wykonać sqs:SendMessage na tej kolejce...
        Sid       = "AllowSNSPublish"
        Effect    = "Allow"
        Principal = { Service = "sns.amazonaws.com" } # kto: usługa SNS (service principal), nie użytkownik/rola
        Action    = "sqs:SendMessage"                 # co: tylko wysyłanie wiadomości
        Resource  = aws_sqs_queue.notifications.arn   # na czym: tylko ta kolejka
        Condition = {
          # ...ale tylko gdy wiadomość pochodzi z NASZEGO tematu — bez tego warunku dowolny temat SNS
          # (także z innego konta) mógłby pisać do kolejki ("confused deputy").
          ArnEquals = {
            "aws:SourceArn" = aws_sns_topic.domain_events.arn
          }
        }
      }
    ]
  })
}

# Subscribe the notifications queue to the domain events topic
# (PL) Subskrypcja łączy temat z kolejką: każda wiadomość z tematu trafia do SQS.
# UWAGA: brak filter_policy — kolejka dostaje wszystkie typy zdarzeń, filtrowanie dzieje się w kodzie.
resource "aws_sns_topic_subscription" "notifications" {
  topic_arn = aws_sns_topic.domain_events.arn
  protocol  = "sqs"                           # typ odbiorcy (inne: lambda, https, email...)
  endpoint  = aws_sqs_queue.notifications.arn # dla protokołu sqs endpoint to ARN kolejki

  # true = do SQS trafia surowa treść zdarzenia, bez "koperty" SNS (Type, MessageId, Message...).
  # Kluczowe dla Javy: konsument parsuje body wprost jako zdarzenie (IncomingEvent).
  raw_message_delivery = true
}
