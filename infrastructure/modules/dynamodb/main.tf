# Tabela DynamoDB Notification Service: rejestr przetworzonych zdarzeń (idempotencja konsumenta SQS)
# i status wysłanych powiadomień. Klucz główny = eventId, więc ten sam event nie zostanie obsłużony dwa razy.
# UWAGA: brak TTL (rekordy rosną bez końca) i brak point-in-time recovery (PITR) — nie da się odtworzyć danych.
resource "aws_dynamodb_table" "notifications" {
  # PAY_PER_REQUEST (on-demand): płacisz za każdy odczyt/zapis, bez planowania pojemności — idealne przy małym,
  # nieregularnym ruchu. Alternatywa PROVISIONED wymaga ustawienia read/write capacity.
  name         = "${var.name_prefix}-notifications"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "eventId" # partition key — unikalny ID zdarzenia z kolejki (PutItem z attribute_not_exists)

  # Deklarujemy tylko atrybuty używane w kluczach (tabeli lub indeksów); pozostałe pola są bez schematu.
  attribute {
    name = "eventId"
    type = "S" # S = String (inne: N = Number, B = Binary)
  }

  # Klucz partycji indeksu GSI — ID klienta.
  attribute {
    name = "customerId"
    type = "S"
  }

  # Klucz sortowania indeksu GSI — znacznik czasu jako String ISO-8601 (sortuje się leksykograficznie).
  attribute {
    name = "createdAt"
    type = "S"
  }

  # Global Secondary Index — alternatywny klucz do zapytań "historia powiadomień klienta, od najnowszych".
  # UWAGA: nikt w kodzie nie odpytuje tego indeksu, a projection ALL kopiuje cały rekord do indeksu —
  # każdy zapis do tabeli to dodatkowy (płatny) zapis do GSI.
  global_secondary_index {
    name            = "customerId-createdAt-index"
    hash_key        = "customerId" # partycja indeksu: wszystkie powiadomienia danego klienta
    range_key       = "createdAt"  # sortowanie w obrębie klienta po czasie utworzenia
    projection_type = "ALL"        # do indeksu kopiowane są wszystkie atrybuty (KEYS_ONLY/INCLUDE byłyby tańsze)
  }

  # Tagi — ułatwiają filtrowanie kosztów i zasobów w konsoli AWS.
  tags = {
    Environment = var.environment
    Service     = "notification-service"
  }
}
