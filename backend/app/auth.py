from datetime import datetime, timedelta, timezone
import secrets

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from fastapi import Cookie, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import settings
from .database import get_db
from .models import User


COOKIE_NAME = "askphotos_session"
CSRF_COOKIE_NAME = "askphotos_csrf"
password_hasher = PasswordHasher()


def hash_password(password: str) -> str:
    return password_hasher.hash(password)


def verify_password(password: str, encoded: str) -> bool:
    try:
        return password_hasher.verify(encoded, password)
    except VerifyMismatchError:
        return False


def create_session_token(user: User) -> str:
    now = datetime.now(timezone.utc)
    return jwt.encode(
        {
            "sub": str(user.id),
            "username": user.username,
            "iat": now,
            "exp": now + timedelta(days=settings.session_days),
        },
        settings.secret_key,
        algorithm="HS256",
    )


def create_csrf_token() -> str:
    return secrets.token_urlsafe(32)


def get_current_user(
    token: str | None = Cookie(default=None, alias=COOKIE_NAME),
    db: Session = Depends(get_db),
) -> User:
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sign in required")
    try:
        payload = jwt.decode(token, settings.secret_key, algorithms=["HS256"])
        user_id = int(payload["sub"])
    except (jwt.InvalidTokenError, KeyError, TypeError, ValueError) as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Session expired") from exc
    user = db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Session expired")
    return user


def require_developer(user: User = Depends(get_current_user)) -> User:
    if not settings.developer_feature_enabled or not user.is_admin or not user.developer_mode:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
    return user


def ensure_admin(db: Session) -> User:
    user = db.scalar(select(User).where(User.username == settings.admin_username))
    if user:
        return user
    user = User(
        username=settings.admin_username,
        password_hash=hash_password(settings.admin_password),
        is_admin=True,
        developer_mode=False,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user
