# Wartości lokalne modułu — liczone raz i używane wielokrotnie.
locals {
  # Mapa klucz -> nazwa repozytorium; po jednym repo ECR na serwis V1 (np. chargeflow-dev-charging-session-service).
  repos = {
    charging_session_service = "${var.name_prefix}-charging-session-service"
    notification_service     = "${var.name_prefix}-notification-service"
  }

  # Lifecycle policy ECR jako JSON — automatyczne usuwanie starych obrazów, żeby nie płacić za storage (~0.10 USD/GB/mies.).
  lifecycle_policy = jsonencode({
    rules = [
      {
        rulePriority = 1 # kolejność oceniania reguł (niższa = wcześniej); tu jest tylko jedna
        description  = "Keep last ${var.image_count_to_keep} images"
        selection = {
          tagStatus   = "any"                   # dotyczy obrazów otagowanych i nieotagowanych
          countType   = "imageCountMoreThan"    # warunek: liczba obrazów przekracza countNumber
          countNumber = var.image_count_to_keep # domyślnie 10 najnowszych zostaje
        }
        action = { type = "expire" } # nadmiarowe (najstarsze) obrazy są usuwane; to jedyny typ akcji w ECR
      }
    ]
  })
}

# Repozytoria ECR (prywatny rejestr Docker w AWS) — CI pushuje tu obrazy, a ECS Fargate je stąd pobiera (przez NAT).
resource "aws_ecr_repository" "repos" {
  # for_each po mapie: powstają repos["charging_session_service"] i repos["notification_service"].
  for_each = local.repos

  name = each.value # zmiana nazwy = usunięcie i utworzenie repo od nowa
  # MUTABLE: ten sam tag (np. latest) można nadpisać. CI taguje obrazy SHA commita, więc tagi są de facto unikalne.
  # IMMUTABLE byłby bezpieczniejszy (gwarancja, że tag zawsze wskazuje ten sam obraz).
  image_tag_mutability = "MUTABLE"

  # Basic scanning — skan CVE obrazu przy każdym pushu (darmowy); wyniki widoczne w konsoli ECR.
  image_scanning_configuration {
    scan_on_push = true
  }

  tags = { Name = each.value }
}

# Podpięcie lifecycle policy do każdego repozytorium.
resource "aws_ecr_lifecycle_policy" "repos" {
  # for_each po zasobach repo — ta sama polityka dla obu repozytoriów.
  for_each = aws_ecr_repository.repos

  repository = each.value.name
  policy     = local.lifecycle_policy
}
