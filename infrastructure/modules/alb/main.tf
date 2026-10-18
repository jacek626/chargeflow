# Application Load Balancer — publiczny punkt wejścia HTTP(S) do ChargeFlow; kieruje ruch do tasków Charging Session Service.
# Koszt: opłata godzinowa + LCU (jednostki ruchu) + publiczne IPv4 węzłów ALB.
resource "aws_lb" "main" {
  name               = "${var.name_prefix}-alb" # max 32 znaki; nazwa wchodzi do publicznego DNS ALB
  internal           = false                    # internet-facing; true = ALB dostępny tylko z wnętrza VPC
  load_balancer_type = "application"            # warstwa 7 (HTTP/HTTPS, routing po ścieżce/hoście); "network" = warstwa 4
  security_groups    = [var.security_group_id]  # alb-sg: 80 i 443 z 0.0.0.0/0
  subnets            = var.public_subnet_ids    # 2 publiczne subnety w 2 AZ (ALB wymaga min. 2 AZ)

  tags = { Name = "${var.name_prefix}-alb" }
}

# Target group — zbiór celów (adresy IP tasków Charging Session Service) + konfiguracja health checka.
# Moduł ecs rejestruje w niej taski przez blok load_balancer serwisu.
resource "aws_lb_target_group" "charging_session" {
  name        = "${var.name_prefix}-css-tg"
  port        = 8080   # port kontenera Charging Session Service, na który ALB wysyła ruch
  protocol    = "HTTP" # ALB -> task zwykłym HTTP wewnątrz VPC (TLS kończy się na ALB)
  vpc_id      = var.vpc_id
  target_type = "ip" # obowiązkowe dla Fargate/awsvpc: rejestrujemy IP ENI taska, nie instancję EC2

  # Health check — ALB okresowo odpytuje każdy task; niezdrowy task jest wyjmowany z ruchu.
  health_check {
    # Spring Boot Actuator; obejmuje stan połączenia z bazą — gdy RDS nie odpowiada, zwraca 503 i task wypada z ruchu.
    path                = "/actuator/health"
    port                = "traffic-port" # sprawdzaj na tym samym porcie co ruch (8080)
    healthy_threshold   = 2              # 2 kolejne sukcesy -> cel healthy
    unhealthy_threshold = 3              # 3 kolejne porażki -> cel unhealthy (przy interval 30 s to ~90 s)
    timeout             = 5              # sekundy oczekiwania na odpowiedź; musi być < interval
    interval            = 30             # co ile sekund sprawdzać
    matcher             = "200"          # tylko HTTP 200 oznacza zdrowy cel
  }

  tags = { Name = "${var.name_prefix}-css-tg" }
}

# HTTP listener — forwards to charging-session-service; redirects to HTTPS when cert is provided
# Listener = proces ALB nasłuchujący na porcie; jego default_action decyduje, co zrobić z żądaniem.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  # Akcja domyślna (brak reguł path-based, bo za ALB stoi jeden serwis).
  # UWAGA: bez certyfikatu (domyślnie w dev) ruch, w tym tokeny JWT, idzie otwartym tekstem.
  default_action {
    # Z certyfikatem: przekierowanie na HTTPS; bez: bezpośrednie przekazanie do target group.
    type = var.acm_certificate_arn != "" ? "redirect" : "forward"

    # dynamic + for_each = [1] lub [] -> blok redirect powstaje tylko, gdy jest certyfikat.
    dynamic "redirect" {
      for_each = var.acm_certificate_arn != "" ? [1] : []
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301" # stałe przekierowanie; przeglądarki mogą je zapamiętać
      }
    }

    # Blok forward powstaje tylko, gdy NIE ma certyfikatu (dokładnie przeciwny warunek do redirect).
    dynamic "forward" {
      for_each = var.acm_certificate_arn == "" ? [1] : []
      content {
        target_group {
          arn = aws_lb_target_group.charging_session.arn
        }
      }
    }
  }
}

# HTTPS listener — only created when an ACM certificate is provided
# TLS kończy się na ALB (TLS termination); do tasków dalej idzie HTTP.
resource "aws_lb_listener" "https" {
  count = var.acm_certificate_arn != "" ? 1 : 0 # count 0/1 = warunkowe tworzenie zasobu

  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  # Polityka TLS: dopuszcza TLS 1.3 i 1.2 z nowoczesnymi szyframi; starsze klienty (TLS 1.0/1.1) nie połączą się.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = var.acm_certificate_arn # certyfikat z ACM (darmowy dla ALB), musi być w tym samym regionie co ALB

  # Każde żądanie HTTPS trafia do target group Charging Session Service.
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.charging_session.arn
  }
}
