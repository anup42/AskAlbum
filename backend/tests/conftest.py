from __future__ import annotations

from collections.abc import Generator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.auth import ensure_admin
from app.config import settings
from app.database import Base, get_db
from app.main import app
from app.photos import seed_demo_library


@pytest.fixture()
def client(tmp_path) -> Generator[TestClient, None, None]:
    database_path = tmp_path / "test.db"
    engine = create_engine(f"sqlite:///{database_path.as_posix()}", connect_args={"check_same_thread": False})
    testing_session = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    Base.metadata.create_all(engine)
    with testing_session() as db:
        ensure_admin(db)
        seed_demo_library(db)

    old_data_dir = settings.data_dir
    settings.data_dir = tmp_path / "data"
    settings.ensure_directories()

    def override_db() -> Generator[Session, None, None]:
        db = testing_session()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_db
    test_client = TestClient(app)
    yield test_client
    test_client.close()
    app.dependency_overrides.clear()
    settings.data_dir = old_data_dir
    engine.dispose()


@pytest.fixture()
def authenticated_client(client: TestClient) -> TestClient:
    response = client.post("/api/auth/login", json={"username": "admin", "password": "askphotos"})
    assert response.status_code == 200
    client.headers["X-CSRF-Token"] = response.json()["csrf_token"]
    return client
