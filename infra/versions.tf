terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
    ec = {
      source  = "elastic/ec"
      version = "~> 0.15"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }

  # Configure a backend for real use, e.g.:
  # backend "s3" { bucket = "<state-bucket>" key = "wokflow/terraform.tfstate" region = "us-west-2" use_lockfile = true }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "wokflow", Environment = var.environment, ManagedBy = "terraform" }
  }
}

# CloudFront certificates and CLOUDFRONT-scope WAF ACLs must live in us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
  default_tags {
    tags = { Project = "wokflow", Environment = var.environment, ManagedBy = "terraform" }
  }
}

# Elasticsearch runs on Elastic Cloud (the 9.x Java client does not talk to Amazon OpenSearch).
provider "ec" {}
