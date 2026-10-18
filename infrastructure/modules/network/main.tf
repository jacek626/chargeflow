# VPC — prywatna, odizolowana sieć ChargeFlow w AWS ("własny budynek" z dokumentacji).
# Wszystkie pozostałe zasoby (subnety, ALB, ECS, RDS) żyją wewnątrz tej VPC.
resource "aws_vpc" "main" {
  # Pula adresów prywatnych całej VPC; domyślnie 10.0.0.0/16 = 65 536 adresów. Nie da się jej później zmniejszyć.
  cidr_block = var.vpc_cidr
  # Zasoby dostają nazwy DNS (np. endpoint RDS xxx.rds.amazonaws.com -> prywatny IP); potrzebne też dla VPC Interface Endpoints.
  enable_dns_hostnames = true
  # Włącza resolver DNS Amazona w VPC (adres bazowy+2); bez niego nazwy AWS się nie rozwiązują.
  enable_dns_support = true

  # Tag Name to nazwa widoczna w konsoli AWS.
  tags = { Name = "${var.name_prefix}-vpc" }
}

# Internet Gateway — "brama" między VPC a internetem. Bez niej nic w VPC nie wyjdzie/nie przyjdzie z internetu.
# Darmowy; korzystają z niego subnety publiczne (ALB) i NAT Gateway.
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id # podpięcie IGW do naszej VPC (jeden IGW na VPC)

  tags = { Name = "${var.name_prefix}-igw" }
}

# ── Public subnets ──────────────────────────────────────────────────────────

# Subnety publiczne (po jednym na AZ) — tu stoi ALB i NAT Gateway, czyli rzeczy osiągalne z internetu.
resource "aws_subnet" "public" {
  # count tworzy tyle subnetów, ile CIDR-ów podano (domyślnie 2 -> public[0], public[1]).
  count = length(var.public_subnet_cidrs)

  vpc_id = aws_vpc.main.id
  # /24 = 256 adresów (AWS rezerwuje 5 w każdym subnecie, zostaje 251). Musi mieścić się w CIDR VPC.
  cidr_block = var.public_subnet_cidrs[count.index]
  # Każdy subnet w innej strefie dostępności — ALB wymaga min. 2 AZ, daje to odporność na awarię AZ.
  availability_zone = var.availability_zones[count.index]
  # Zasoby uruchomione tu automatycznie dostają publiczny IPv4 (płatny godzinowo od 2024). To "publiczność" subnetu razem z trasą do IGW.
  map_public_ip_on_launch = true

  # count.index + 1, żeby nazwy były public-1, public-2 zamiast od zera.
  tags = { Name = "${var.name_prefix}-public-${count.index + 1}" }
}

# Tablica routingu dla subnetów publicznych — to ona sprawia, że subnet jest "publiczny".
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  # Trasa domyślna: każdy ruch spoza VPC (0.0.0.0/0 = "cały internet") idzie przez Internet Gateway.
  # Ruch wewnątrz VPC (10.0.0.0/16) obsługuje niejawna trasa "local".
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${var.name_prefix}-rt-public" }
}

# Powiązanie każdego subnetu publicznego z publiczną tablicą routingu (bez tego subnet używa głównej tablicy VPC, bez IGW).
resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id # jedna tablica współdzielona przez wszystkie subnety publiczne
}

# ── NAT Gateway (single, cost-effective for dev) ────────────────────────────

# Elastic IP — stały publiczny adres IPv4 dla NAT Gateway; z tego adresu wychodzi cały ruch z subnetów prywatnych.
# Płatny godzinowo (publiczny IPv4), nawet gdy nic nie działa.
resource "aws_eip" "nat" {
  domain = "vpc" # EIP do użycia w VPC (nowsza składnia zamiast przestarzałego vpc = true)

  tags = { Name = "${var.name_prefix}-nat-eip" }
}

# NAT Gateway — pozwala taskom ECS bez publicznego IP INICJOWAĆ połączenia wychodzące (ECR, Secrets Manager, SNS/SQS, logi),
# ale nikt z internetu nie połączy się do nich. Koszt: opłata godzinowa (~30-40 USD/mies.) + za każdy przetworzony GB.
# UWAGA: jeden NAT tylko w AZ a = single point of failure; awaria AZ a odcina wyjście taskom z AZ b. Dla prod: NAT + route table per AZ.
# UWAGA: brak VPC Endpoints (np. darmowy Gateway endpoint S3, endpointy ECR/logs) — cały ruch do usług AWS, w tym pull obrazów, idzie płatnie przez NAT.
resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id # przypięcie publicznego Elastic IP
  # NAT musi stać w subnecie PUBLICZNYM (sam potrzebuje drogi do IGW); tu public[0] = eu-central-1a.
  subnet_id = aws_subnet.public[0].id

  tags = { Name = "${var.name_prefix}-nat" }

  # Jawna zależność: NAT nie odwołuje się do IGW w atrybutach, ale bez działającego IGW nie ma wyjścia do internetu (zalecenie providera).
  depends_on = [aws_internet_gateway.main]
}

# ── Private subnets ─────────────────────────────────────────────────────────

# Subnety prywatne (po jednym na AZ) — tu działają taski ECS Fargate i RDS; brak publicznych IP, niedostępne z internetu.
resource "aws_subnet" "private" {
  count = length(var.private_subnet_cidrs)

  vpc_id = aws_vpc.main.id
  # Np. 10.0.10.0/24 i 10.0.20.0/24 — osobny zakres od publicznych, łatwo rozpoznać po trzecim oktecie.
  cidr_block = var.private_subnet_cidrs[count.index]
  # Ta sama para AZ co subnety publiczne; RDS subnet group też wymaga min. 2 AZ.
  availability_zone = var.availability_zones[count.index]
  # Brak map_public_ip_on_launch (domyślnie false) — to celowe: zasoby tu nie dostają publicznego IP.

  tags = { Name = "${var.name_prefix}-private-${count.index + 1}" }
}

# Tablica routingu dla subnetów prywatnych: ruch do internetu idzie przez NAT, a nie bezpośrednio przez IGW.
# UWAGA: jedna tablica dla obu AZ — task w AZ b wychodzi przez NAT w AZ a (dodatkowa opłata za ruch cross-AZ).
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  # Trasa domyślna 0.0.0.0/0 -> NAT Gateway: tylko połączenia wychodzące; odpowiedzi wracają, nowe połączenia z zewnątrz nie.
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main.id
  }

  tags = { Name = "${var.name_prefix}-rt-private" }
}

# Powiązanie każdego subnetu prywatnego z prywatną tablicą routingu.
resource "aws_route_table_association" "private" {
  count = length(aws_subnet.private)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# ── Security groups ─────────────────────────────────────────────────────────
# Security Group = stanowy firewall na poziomie ENI. Stanowy: odpowiedź na dozwolone połączenie przechodzi bez osobnej reguły.
# Łańcuch w ChargeFlow: internet -80/443-> sg:alb -8080-> sg:ecs -5432-> sg:rds.

# SG dla ALB — jedyny punkt wejścia z internetu.
resource "aws_security_group" "alb" {
  name = "${var.name_prefix}-alb-sg"
  # Zmiana description wymusza odtworzenie SG (replace), więc lepiej jej nie ruszać.
  description = "Allow HTTP/HTTPS inbound from internet"
  vpc_id      = aws_vpc.main.id

  # HTTP z całego internetu (0.0.0.0/0). Bez certyfikatu ACM ALB serwuje ruch po HTTP — dane (np. JWT) lecą otwartym tekstem.
  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80 # from/to = zakres portów; tu pojedynczy port 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # HTTPS z całego internetu; używany, gdy podano acm_certificate_arn (listener 443 w module alb).
  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Ruch wychodzący bez ograniczeń — ALB musi łączyć się z taskami ECS (8080) i wykonywać health checki.
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1" # "-1" = wszystkie protokoły; wtedy porty 0-0 oznaczają "wszystkie porty"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.name_prefix}-alb-sg" }
}

# SG dla tasków ECS Fargate (oba serwisy) — wpuszcza ruch tylko od ALB, nie z internetu.
resource "aws_security_group" "ecs" {
  name        = "${var.name_prefix}-ecs-sg"
  description = "Allow inbound from ALB; allow all outbound"
  vpc_id      = aws_vpc.main.id

  # Port 8080 Charging Session Service; źródłem jest SG ALB (referencja do SG zamiast CIDR = tylko ENI z tym SG).
  ingress {
    description     = "Charging Session Service from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # Port 8081 Notification Service od ALB.
  # UWAGA: reguła martwa — moduł alb nie ma target group dla 8081; Notification Service nie jest za ALB (tylko poll SQS).
  ingress {
    description     = "Notification Service from ALB"
    from_port       = 8081
    to_port         = 8081
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # Wyjście bez ograniczeń: taski łączą się z RDS (5432) i przez NAT z usługami AWS (ECR, Secrets Manager, SNS/SQS, DynamoDB, SES, Cognito, logi).
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.name_prefix}-ecs-sg" }
}

# SG dla bazy RDS PostgreSQL — dostęp wyłącznie z tasków ECS.
resource "aws_security_group" "rds" {
  name        = "${var.name_prefix}-rds-sg"
  description = "Allow PostgreSQL from ECS tasks only"
  vpc_id      = aws_vpc.main.id

  # Port PostgreSQL 5432 tylko od zasobów z SG ecs — baza niedostępna z internetu ani z ALB.
  ingress {
    description     = "PostgreSQL from ECS"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  # UWAGA: egress 0.0.0.0/0 zbędny — baza nie inicjuje połączeń, a SG jest stanowy (odpowiedzi przechodzą same).
  # Zgodnie z least privilege tę regułę można usunąć.
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.name_prefix}-rds-sg" }
}
