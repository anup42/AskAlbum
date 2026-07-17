from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

PROJECT_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="ASKPHOTOS_",
        case_sensitive=False,
        extra="ignore",
    )

    env: str = "development"
    secret_key: str = "local-development-secret-key-change-me"
    admin_username: str = "admin"
    admin_password: str = "askphotos"
    database_url: str = f"sqlite:///{(PROJECT_ROOT / 'backend' / 'data' / 'askphotos.db').as_posix()}"
    redis_url: str = "redis://127.0.0.1:6379/0"
    celery_always_eager: bool = True
    auto_index_on_start: bool = False
    local_models_enabled: bool = False
    data_dir: Path = Field(default=PROJECT_ROOT / "backend" / "data")
    demo_dir: Path = Field(default=PROJECT_ROOT / "demo-assets")
    developer_feature_enabled: bool = True
    session_days: int = 7
    cookie_secure: bool = False
    max_upload_bytes: int = 250 * 1024 * 1024
    upload_chunk_bytes: int = 4 * 1024 * 1024
    qwen_model_dir: Path | None = None
    siglip_model_dir: Path | None = None
    paddleocr_model_dir: Path | None = None
    sface_model_path: Path | None = None
    whisper_model_dir: Path | None = None
    model_device: str = "cuda"
    model_service_url: str = "http://127.0.0.1:8091"
    qwen_base_url: str = "http://127.0.0.1:8092/v1"
    qwen_model_name: str = "Qwen3-VL-8B-Instruct-FP8"
    yunet_model_path: Path | None = None
    pipeline_version: str = "local-multimodal-v1"
    model_request_timeout_seconds: float = 90.0
    search_stream_candidate_limit: int = 80
    visual_verification_limit: int = 6
    rate_limit_enabled: bool = False
    rate_limit_per_minute: int = 120
    trusted_proxy_count: int = 0

    @field_validator("data_dir", "demo_dir", mode="before")
    @classmethod
    def expand_path(cls, value: str | Path) -> Path:
        return Path(value).expanduser().resolve()

    def ensure_directories(self) -> None:
        for path in (
            self.data_dir,
            self.data_dir / "originals",
            self.data_dir / "derived",
            self.data_dir / "uploads",
        ):
            path.mkdir(parents=True, exist_ok=True)

    def validate_production(self) -> None:
        if self.env.lower() == "production":
            if self.admin_password == "askphotos":
                raise RuntimeError("ASKPHOTOS_ADMIN_PASSWORD must be changed in production")
            if len(self.secret_key) < 32 or "change-me" in self.secret_key:
                raise RuntimeError("ASKPHOTOS_SECRET_KEY must be a strong production secret")


settings = Settings()
