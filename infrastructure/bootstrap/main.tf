# Bootstrap — creates the S3 bucket and DynamoDB table used for Terraform remote state.
# Run once manually before the CD pipeline:
#   cd infrastructure/bootstrap
#   terraform init && terraform apply

# Bucket S3 na plik stanu Terraform (terraform.tfstate) środowiska dev (a w przyszłości prod).
# Stan = mapowanie kodu na realne zasoby AWS; jego utrata oznacza, że Terraform "zapomina" infrastrukturę.
resource "aws_s3_bucket" "tfstate" {
  bucket        = var.state_bucket_name # nazwa globalnie unikalna w całym AWS (stąd sugerowany sufiks z ID konta)
  force_destroy = false                 # false = destroy NIE usunie niepustego bucketu - ochrona przed utratą stanu

  # Tagi ustawione ręcznie - bootstrap nie ma default_tags w providerze
  tags = { Name = var.state_bucket_name, ManagedBy = "Terraform" }
}

# Wersjonowanie bucketu: każdy zapis stanu tworzy nową wersję obiektu,
# więc uszkodzony lub nadpisany tfstate można przywrócić z poprzedniej wersji.
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id # referencja do bucketu powyżej - Terraform sam ustali kolejność tworzenia (graf zależności)

  versioning_configuration {
    status = "Enabled" # "Enabled" włącza; "Suspended" tylko wstrzymuje (wersjonowania nie da się całkiem wyłączyć)
  }
}

# Szyfrowanie "at rest" - plik stanu zawiera sekrety (np. hasło RDS) jawnym tekstem.
resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  # Reguła domyślna: każdy nowy obiekt w buckecie jest automatycznie szyfrowany
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # AES256 = SSE-S3 (klucze zarządza AWS, bez dodatkowych kosztów); alternatywa: aws:kms
    }
  }
}

# Blokada publicznego dostępu - bucket ze stanem nigdy nie może być publiczny.
resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true # odrzuca próby nadania publicznych ACL bucketowi/obiektom
  block_public_policy     = true # odrzuca bucket policy, która dawałaby dostęp publiczny
  ignore_public_acls      = true # ignoruje ewentualne istniejące publiczne ACL
  restrict_public_buckets = true # nawet przy publicznej polityce dostęp mają tylko usługi AWS i właściciel konta
}

# Tabela DynamoDB do blokady stanu (state locking): chroni przed dwoma równoczesnymi
# terraform apply (np. CI i laptop), które mogłyby uszkodzić plik stanu.
# UWAGA: od Terraform 1.11 backend S3 ma natywny lock w S3 (use_lockfile = true),
# a parametr dynamodb_table jest deprecated - ta tabela działa, ale nie jest już konieczna.
resource "aws_dynamodb_table" "tfstate_lock" {
  name         = var.lock_table_name # nazwa podawana potem przy terraform init -backend-config="dynamodb_table=..."
  billing_mode = "PAY_PER_REQUEST"   # płatność za żądanie, bez stałej przepustowości - przy kilku lockach dziennie koszt ~0
  hash_key     = "LockID"            # klucz partycji; backend S3 wymaga dokładnie nazwy LockID

  # Typ atrybutu klucza (w DynamoDB deklaruje się tylko atrybuty używane w kluczach)
  attribute {
    name = "LockID" # musi zgadzać się z hash_key
    type = "S"      # S = string (N = liczba, B = binarne)
  }

  # Tagi ustawione ręcznie (brak default_tags w providerze bootstrapu)
  tags = { Name = var.lock_table_name, ManagedBy = "Terraform" }
}
