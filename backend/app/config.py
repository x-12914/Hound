from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    secret_key: str = "dev-insecure-secret-change-me"
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 10080  # 7 days

    database_url: str = "sqlite:///./hound.db"
    media_dir: str = "./media"

    cors_origins: str = "*"

    admin_email: str = "admin@example.com"
    admin_password: str = "change-me"

    @property
    def cors_origin_list(self) -> list[str]:
        if self.cors_origins.strip() == "*":
            return ["*"]
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
