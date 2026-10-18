# Prywatny bucket S3 — "magazyn" na zbudowane pliki Angular SPA (index.html, JS, CSS).
# Nie jest hostingiem www: pliki serwuje wyłącznie CloudFront.
resource "aws_s3_bucket" "frontend" {
  bucket        = "${var.name_prefix}-frontend" # nazwa bucketa musi być globalnie unikalna w całym AWS
  force_destroy = true                          # terraform destroy usunie bucket nawet z plikami (ok dla dev)

  tags = { Name = "${var.name_prefix}-frontend" }
}

# Wersjonowanie obiektów — nadpisany/usunięty plik zostaje jako poprzednia wersja (możliwy rollback).
# UWAGA: brak lifecycle rule (np. noncurrent_version_expiration) — stare wersje po każdym deployu
# kumulują się bez końca i podnoszą koszt przechowywania.
resource "aws_s3_bucket_versioning" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Block Public Access — twarda blokada publicznego dostępu; bucket pozostaje prywatny,
# a dostęp ma tylko CloudFront przez OAC + bucket policy.
resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true # odrzuca próby nadania publicznych ACL
  block_public_policy     = true # odrzuca bucket policy dającą publiczny dostęp (nasza jest dla konkretnego principala, więc OK)
  ignore_public_acls      = true # ignoruje istniejące publiczne ACL
  restrict_public_buckets = true # nawet przy publicznej polityce dostęp mają tylko usługi AWS/konto właściciela
}

# Origin Access Control — CloudFront accesses S3 using sigv4
# OAC to następca OAI: "legitymacja kuriera" — CloudFront podpisuje żądania do S3, bucket wpuszcza tylko jego.
resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${var.name_prefix}-frontend-oac"
  description                       = "OAC for ${var.name_prefix} frontend"
  origin_access_control_origin_type = "s3"     # origin jest bucketem S3
  signing_behavior                  = "always" # zawsze podpisuj żądania do originu
  signing_protocol                  = "sigv4"  # podpis AWS Signature Version 4
}

# Bucket policy — pozwala czytać pliki wyłącznie usłudze CloudFront, i to tylko naszej dystrybucji.
resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  policy = jsonencode({
    Version = "2012-10-17" # stała wersja języka polityk IAM
    Statement = [{
      Sid    = "AllowCloudFrontRead"
      Effect = "Allow"
      Principal = {
        Service = "cloudfront.amazonaws.com" # principal = usługa CloudFront (podpisująca przez OAC)
      }
      Action   = "s3:GetObject"                    # tylko odczyt plików, bez listowania/zapisu
      Resource = "${aws_s3_bucket.frontend.arn}/*" # wszystkie obiekty w buckecie
      # Bez SourceArn dowolna dystrybucja CloudFront (także z obcego konta) mogłaby czytać bucket.
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.frontend.arn
        }
      }
    }]
  })
}

# Dystrybucja CloudFront (CDN) — publiczny punkt wejścia dla frontendu ChargeFlow, serwuje SPA z edge locations.
# UWAGA: brak price_class => domyślnie PriceClass_All (wszystkie edge locations, najdroższa opcja);
# dla dev zwykle wystarczy PriceClass_100 (Europa + Ameryka Płn.).
resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true               # dystrybucja aktywna (przyjmuje ruch)
  is_ipv6_enabled     = true               # obsługa klientów IPv6
  default_root_object = "index.html"       # żądanie "/" zwraca index.html
  aliases             = var.domain_aliases # własne domeny (CNAME); wymagają certyfikatu ACM

  # Źródło (origin) plików — prywatny bucket S3, dostęp podpisywany przez OAC.
  origin {
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name # regionalny endpoint REST S3 (nie website endpoint)
    origin_id                = "s3-frontend"                                      # lokalna nazwa originu, wskazywana w cache behavior
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  # Domyślne zachowanie cache dla wszystkich ścieżek.
  default_cache_behavior {
    target_origin_id       = "s3-frontend"              # z którego originu pobierać
    viewer_protocol_policy = "redirect-to-https"        # HTTP -> 301 na HTTPS
    allowed_methods        = ["GET", "HEAD", "OPTIONS"] # statyczne pliki — tylko odczyt
    cached_methods         = ["GET", "HEAD"]            # które odpowiedzi trafiają do cache
    compress               = true                       # automatyczna kompresja gzip/brotli

    # UWAGA: forwarded_values to przestarzały (legacy) sposób konfiguracji cache;
    # AWS zaleca cache_policy_id (np. managed policy CachingOptimized).
    forwarded_values {
      query_string = false         # nie przekazuj query stringów => jeden obiekt w cache na ścieżkę
      cookies { forward = "none" } # nie przekazuj cookies — statyczne SPA ich nie potrzebuje
    }

    min_ttl     = 0     # minimalny czas w cache (s)
    default_ttl = 3600  # 1 h, gdy origin nie poda Cache-Control
    max_ttl     = 86400 # maksymalnie 24 h
  }

  # Angular SPA routing — return index.html for all 404s
  # Np. /reservations nie istnieje jako plik w S3 — zwracamy index.html, a routing obsłuży Angular.
  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0 # nie cache'uj błędu
  }

  # S3 za OAC zwraca 403 (nie 404) dla nieistniejącego klucza, gdy brak s3:ListBucket — dlatego też 403 -> index.html.
  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  # Ograniczenia geograficzne — brak (dostęp z każdego kraju).
  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # Wariant 1: podano certyfikat ACM => HTTPS na własnej domenie (aliases).
  # dynamic + for_each [1]/[] to wzorzec "warunkowego bloku" w Terraform.
  dynamic "viewer_certificate" {
    for_each = var.acm_certificate_arn != "" ? [1] : []
    content {
      acm_certificate_arn      = var.acm_certificate_arn
      ssl_support_method       = "sni-only"     # SNI — bez dodatkowo płatnego dedykowanego IP
      minimum_protocol_version = "TLSv1.2_2021" # minimum TLS 1.2 z nowoczesnymi szyframi
    }
  }

  # Wariant 2: brak certyfikatu => domyślny certyfikat CloudFront (*.cloudfront.net).
  dynamic "viewer_certificate" {
    for_each = var.acm_certificate_arn == "" ? [1] : []
    content {
      cloudfront_default_certificate = true
    }
  }

  tags = { Name = "${var.name_prefix}-frontend" }
}
