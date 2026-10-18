# GitHub Actions OIDC provider — allows GitHub to obtain short-lived AWS credentials
# without storing long-lived access keys in GitHub Secrets.
# W IAM to wpis "ufam tokenom JWT wystawionym przez GitHub Actions" (jeden na konto AWS dla danego URL).
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com" # issuer tokenów OIDC GitHub Actions
  client_id_list  = ["sts.amazonaws.com"]                         # oczekiwane "aud" tokenu — odbiorcą jest AWS STS
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]  # odcisk certyfikatu TLS GitHuba; dla GitHuba AWS i tak weryfikuje go sam (wartość wymagana historycznie)

  tags = { Name = "github-actions-oidc" }
}

# Deployment role assumed by GitHub Actions workflows
# Rola, którą workflow deploy.yml przejmuje przez STS AssumeRoleWithWebIdentity, dostając tymczasowe credentials.
resource "aws_iam_role" "deploy" {
  name = "${var.name_prefix}-github-deploy"

  # Trust policy — KTO może przejąć rolę (a nie CO rola może robić).
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.github.arn # zaufany zewnętrzny dostawca tożsamości (OIDC GitHub)
      }
      Action = "sts:AssumeRoleWithWebIdentity" # wymiana tokenu JWT GitHuba na tymczasowe klucze AWS
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" # token musi być wystawiony dla STS
        }
        # UWAGA: wildcard "repo:<repo>:*" pozwala przejąć rolę z dowolnego brancha, taga, environmentu
        # i pull requesta w tym repo; bezpieczniej np. "repo:<repo>:ref:refs/heads/main".
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:*"
        }
      }
    }]
  })

  tags = { Name = "${var.name_prefix}-github-deploy" }
}

# AdministratorAccess for DEV — appropriate for an IaC deployment role in non-production.
# Podpina zarządzaną przez AWS politykę uprawnień (CO rola może robić) do roli deploy.
# UWAGA: AdministratorAccess = pełne uprawnienia do konta; w połączeniu z wildcardem w "sub"
# każdy workflow z dowolnego brancha/PR w repo może zrobić w koncie wszystko. Docelowo least privilege.
resource "aws_iam_role_policy_attachment" "deploy_admin" {
  role       = aws_iam_role.deploy.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess" # "aws" w ARN = polityka zarządzana przez AWS
}
