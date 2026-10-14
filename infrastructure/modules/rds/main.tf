resource "random_password" "db" {
  length           = 32
  special          = true
  override_special = "!#$%^&*-_=+"
}

resource "aws_secretsmanager_secret" "db_password" {
  name                    = "${var.name_prefix}/rds/db-password"
  description             = "RDS master password for ${var.name_prefix}"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db.result
}

resource "aws_db_subnet_group" "main" {
  name       = "${var.name_prefix}-db-subnet-group"
  subnet_ids = var.subnet_ids

  tags = { Name = "${var.name_prefix}-db-subnet-group" }
}

resource "aws_db_parameter_group" "main" {
  name   = "${var.name_prefix}-pg${var.postgres_version}"
  family = "postgres${var.postgres_version}"

  tags = { Name = "${var.name_prefix}-pg${var.postgres_version}" }
}

resource "aws_db_instance" "main" {
  identifier = "${var.name_prefix}-postgres"

  engine         = "postgres"
  engine_version = var.postgres_version
  instance_class = var.instance_class

  allocated_storage = var.allocated_storage_gb
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [var.security_group_id]
  parameter_group_name   = aws_db_parameter_group.main.name

  multi_az            = false
  publicly_accessible = false
  skip_final_snapshot = true
  deletion_protection = var.deletion_protection

  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:04:00-sun:05:00"

  tags = { Name = "${var.name_prefix}-postgres" }
}
