# Wyjścia modułu — wartości przekazywane dalej (np. do modułu ecs jako konfiguracja połączenia z bazą).
# Pełny adres "host:port" — do jednorazowego podglądu / debugowania.
output "endpoint" {
  description = "RDS instance endpoint (host:port)"
  value       = aws_db_instance.main.endpoint
}

# Sam hostname (bez portu) — używany do zbudowania JDBC URL w serwisie.
output "host" {
  description = "RDS instance hostname"
  value       = aws_db_instance.main.address
}

# Port PostgreSQL (domyślnie 5432).
output "port" {
  description = "RDS instance port"
  value       = aws_db_instance.main.port
}

# Nazwa bazy utworzonej w instancji.
output "db_name" {
  description = "Database name"
  value       = aws_db_instance.main.db_name
}

# Login użytkownika master (to nie jest sekret, w przeciwieństwie do hasła).
output "db_username" {
  description = "Database master username"
  value       = aws_db_instance.main.username
}

# ARN sekretu (nie samo hasło!) — ECS pobiera wartość z Secrets Manager przy starcie zadania.
output "db_password_secret_arn" {
  description = "ARN of the Secrets Manager secret containing the DB password"
  value       = aws_secretsmanager_secret.db_password.arn
}
