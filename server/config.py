"""Единая конфигурация сервера Senim из переменных окружения."""

from dataclasses import dataclass
import os
from pathlib import Path


def _read_env_file(path: Path) -> None:
    """Загружает простой .env без дополнительной зависимости.

    Значения, уже заданные окружением, имеют приоритет.
    """
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def _positive_int(name: str, default: int) -> int:
    value = int(os.getenv(name, str(default)))
    if value <= 0:
        raise ValueError(f"{name} должен быть положительным целым числом")
    return value


def _score(name: str, default: float) -> float:
    value = float(os.getenv(name, str(default)))
    if not 0.0 <= value <= 1.0:
        raise ValueError(f"{name} должен быть числом от 0 до 1")
    return value


@dataclass(frozen=True, slots=True)
class Settings:
    host: str
    port: int
    database_path: Path
    risk_window_minutes: int
    risk_amount_threshold: int
    risk_amount_threshold_with_signal: int
    transfer_pending_seconds: int
    decoy_ttl_seconds: int
    call_alert_score: float
    offline_limit: int
    fcm_server_key: str | None


def load_settings() -> Settings:
    server_dir = Path(__file__).resolve().parent
    _read_env_file(server_dir / ".env")

    return Settings(
        host=os.getenv("HOST", "0.0.0.0"),
        port=_positive_int("PORT", 8000),
        database_path=Path(os.getenv("DATABASE_PATH", "senim.db")),
        risk_window_minutes=_positive_int("RISK_WINDOW_MINUTES", 30),
        risk_amount_threshold=_positive_int("RISK_AMOUNT_THRESHOLD", 100_000),
        risk_amount_threshold_with_signal=_positive_int(
            "RISK_AMOUNT_THRESHOLD_WITH_SIGNAL", 50_000
        ),
        transfer_pending_seconds=_positive_int("TRANSFER_PENDING_SECONDS", 120),
        decoy_ttl_seconds=_positive_int("DECOY_TTL_SECONDS", 300),
        call_alert_score=_score("CALL_ALERT_SCORE", 0.85),
        offline_limit=_positive_int("OFFLINE_LIMIT", 50_000),
        fcm_server_key=os.getenv("FCM_SERVER_KEY") or None,
    )


settings = load_settings()

