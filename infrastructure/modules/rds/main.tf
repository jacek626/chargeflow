# Generator losowego hasła dla użytkownika master bazy RDS (provider "random", nie AWS).
# UWAGA: wynik (result) trafia do Terraform state jawnym tekstem — sprzeczne z ARCHITECTURE.md
# ("No credentials in code or Terraform state files"). Lepiej: manage_master_user_password = true w RDS.
resource "random_password" "db" {
  # override_special: tylko te znaki specjalne — RDS nie akceptuje w haśle m.in. '/', '@', '"' i spacji.
  length           = 32   # 32 znaki — długie, trudne do zgadnięcia hasło
  special          = true # dołącz znaki specjalne
  override_special = "!#$%^&*-_=+"
}

# Sekret w AWS Secrets Manager — "sejf" na hasło do bazy; ECS wstrzykuje go do kontenera jako zmienną środowiskową.
resource "aws_secretsmanager_secret" "db_password" {
  # recovery_window_in_days = 0 → natychmiastowe usunięcie przy destroy (bez 7–30 dni okresu odzyskiwania);
  # wygodne w dev, bo można od razu odtworzyć sekret o tej samej nazwie. W prod zwykle 7+ dni.
  name                    = "${var.name_prefix}/rds/db-password" # ścieżka w nazwie ułatwia porządek i IAM po prefiksie
  description             = "RDS master password for ${var.name_prefix}"
  recovery_window_in_days = 0
}

# Wersja sekretu = faktyczna wartość zapisana w sekrecie (sam "secret" to tylko kontener/metadane).
# UWAGA: brak rotacji hasła — ustawione raz przez Terraform, nikt go automatycznie nie zmienia.
resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id # do którego sekretu zapisujemy wartość
  secret_string = random_password.db.result                # wygenerowane hasło (przez to widoczne także w state)
}

# DB subnet group — lista podsieci (w różnych AZ), w których RDS może umieścić instancję.
# Podajemy prywatne podsieci, więc baza nie ma drogi do/z internetu.
resource "aws_db_subnet_group" "main" {
  name       = "${var.name_prefix}-db-subnet-group"
  subnet_ids = var.subnet_ids # prywatne podsieci z modułu sieci (wymagane min. 2 AZ)

  tags = { Name = "${var.name_prefix}-db-subnet-group" }
}

# Parameter group — zestaw ustawień silnika PostgreSQL (odpowiednik postgresql.conf).
# Tu bez własnych parametrów, ale własna grupa pozwala je później zmienić bez podmiany grupy na instancji.
resource "aws_db_parameter_group" "main" {
  name   = "${var.name_prefix}-pg${var.postgres_version}"
  family = "postgres${var.postgres_version}" # rodzina musi pasować do głównej wersji silnika (np. postgres16)

  tags = { Name = "${var.name_prefix}-pg${var.postgres_version}" }
}

# Właściwa instancja bazy RDS PostgreSQL — magazyn danych Charging Session Service (sesje, outbox).
resource "aws_db_instance" "main" {
  identifier = "${var.name_prefix}-postgres" # nazwa instancji w konsoli AWS i w hostname endpointu

  # UWAGA: wersja domyślnie "16", a lokalnie docker-compose używa postgres:18.6 — niespójność dev vs AWS.
  engine         = "postgres"
  engine_version = var.postgres_version
  instance_class = var.instance_class # rozmiar maszyny (CPU/RAM), domyślnie najmniejsza db.t4g.micro

  allocated_storage = var.allocated_storage_gb # rozmiar dysku w GiB
  storage_type      = "gp3"                    # SSD ogólnego przeznaczenia; gp3 tańszy, bazowy IOPS niezależny od rozmiaru
  storage_encrypted = true                     # szyfrowanie danych "at rest" kluczem KMS (domyślny klucz AWS)

  # UWAGA: hasło z random_password zamiast manage_master_user_password = true —
  # ląduje w Terraform state i nie jest rotowane.
  db_name  = var.db_name     # baza tworzona przy starcie instancji
  username = var.db_username # użytkownik master (aplikacja łączy się właśnie nim)
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name # w których podsieciach stoi baza
  vpc_security_group_ids = [var.security_group_id]       # firewall: wpuszcza 5432 tylko z SG serwisów ECS
  parameter_group_name   = aws_db_parameter_group.main.name

  # UWAGA: ustawienia dev — skip_final_snapshot = true + deletion_protection = false (domyślnie)
  # oznaczają, że terraform destroy bezpowrotnie kasuje bazę wraz z danymi (bez końcowego snapshotu).
  multi_az            = false                   # brak repliki standby w drugiej AZ — taniej, ale awaria AZ = przestój
  publicly_accessible = false                   # brak publicznego IP — baza osiągalna tylko z wnętrza VPC
  skip_final_snapshot = true                    # przy usunięciu instancji nie rób końcowego snapshotu
  deletion_protection = var.deletion_protection # true blokuje usunięcie instancji (także przez Terraform)

  backup_retention_period = 7                     # automatyczne backupy trzymane 7 dni (umożliwiają point-in-time restore)
  backup_window           = "03:00-04:00"         # okno backupu w UTC — w nocy, przy małym ruchu
  maintenance_window      = "sun:04:00-sun:05:00" # okno patchy AWS (UTC); nie może nachodzić na backup

  tags = { Name = "${var.name_prefix}-postgres" }
}
