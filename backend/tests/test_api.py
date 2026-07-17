from __future__ import annotations

from io import BytesIO

from fastapi.testclient import TestClient
from PIL import Image

from app.config import settings


def jpeg_bytes() -> bytes:
    output = BytesIO()
    Image.new("RGB", (48, 32), color=(28, 115, 92)).save(output, format="JPEG")
    return output.getvalue()


def test_private_endpoints_require_authentication(client: TestClient) -> None:
    assert client.get("/api/photos").status_code == 401
    assert client.post("/api/search", json={"query": "lake"}).status_code == 401
    assert client.get("/api/developer/status").status_code == 401


def test_demo_library_and_grounded_search(authenticated_client: TestClient) -> None:
    gallery = authenticated_client.get("/api/photos?scope=demo")
    assert gallery.status_code == 200
    assert gallery.json()["total"] == 14
    assert "status" not in gallery.json()["items"][0]
    assert "pipeline_version" not in gallery.json()["items"][0]

    search = authenticated_client.post("/api/search", json={"query": "sunset beach", "scope": "demo"})
    assert search.status_code == 200
    body = search.json()
    assert body["items"]
    assert body["evidence_photo_ids"] == [item["id"] for item in body["items"]]
    assert all(item["scope"] == "demo" for item in body["items"])


def test_developer_details_are_server_authorized(authenticated_client: TestClient) -> None:
    hidden = authenticated_client.get("/api/developer/status")
    assert hidden.status_code == 404

    enabled = authenticated_client.put("/api/settings", json={"developer_mode": True})
    assert enabled.status_code == 200
    diagnostics = authenticated_client.get("/api/developer/status")
    assert diagnostics.status_code == 200
    assert diagnostics.json()["pipeline_version"] == settings.pipeline_version

    authenticated_client.put("/api/settings", json={"developer_mode": False})
    assert authenticated_client.get("/api/developer/status").status_code == 404


def test_resumable_upload_uses_content_addressed_storage(authenticated_client: TestClient) -> None:
    content = jpeg_bytes()
    created = authenticated_client.post(
        "/api/uploads/sessions",
        json={
            "filename": "green-hills.jpg",
            "relative_path": "Summer/Day 1/green-hills.jpg",
            "content_type": "image/jpeg",
            "size": len(content),
        },
    )
    assert created.status_code == 200
    upload = created.json()

    split = len(content) // 2
    first = authenticated_client.patch(
        f"/api/uploads/sessions/{upload['id']}",
        headers={"Upload-Offset": "0", "Content-Type": "application/offset+octet-stream"},
        content=content[:split],
    )
    assert first.status_code == 200
    assert first.json()["offset"] == split

    mismatch = authenticated_client.patch(
        f"/api/uploads/sessions/{upload['id']}",
        headers={"Upload-Offset": "0", "Content-Type": "application/offset+octet-stream"},
        content=content[split:],
    )
    assert mismatch.status_code == 409
    assert mismatch.headers["Upload-Offset"] == str(split)

    second = authenticated_client.patch(
        f"/api/uploads/sessions/{upload['id']}",
        headers={"Upload-Offset": str(split), "Content-Type": "application/offset+octet-stream"},
        content=content[split:],
    )
    assert second.status_code == 200

    completed = authenticated_client.post(f"/api/uploads/sessions/{upload['id']}/complete")
    assert completed.status_code == 200
    photo = completed.json()["photo"]
    assert photo["scope"] == "personal"
    assert photo["relative_path"] == "Summer/Day 1/green-hills.jpg"
    assert "/content?variant=original" in photo["image_url"]
    assert authenticated_client.get(photo["image_url"]).status_code == 200


def test_upload_rejects_path_traversal(authenticated_client: TestClient) -> None:
    response = authenticated_client.post(
        "/api/uploads/sessions",
        json={"filename": "photo.jpg", "relative_path": "../private/photo.jpg", "size": 10},
    )
    assert response.status_code == 422


def test_authenticated_writes_require_csrf(client: TestClient) -> None:
    login = client.post("/api/auth/login", json={"username": "admin", "password": "askphotos"})
    assert login.status_code == 200
    blocked = client.put("/api/settings", json={"developer_mode": True})
    assert blocked.status_code == 403
    accepted = client.put(
        "/api/settings",
        headers={"X-CSRF-Token": login.json()["csrf_token"]},
        json={"developer_mode": True},
    )
    assert accepted.status_code == 200


def test_stream_search_sends_grounded_cards_before_completion(authenticated_client: TestClient) -> None:
    response = authenticated_client.post(
        "/api/search/stream",
        json={"query": "sunset beach", "scope": "demo", "limit": 10},
    )
    assert response.status_code == 200
    body = response.text
    assert body.index("event: results") < body.index("event: done")
    assert "evidence_photo_ids" in body
    assert "event: answer" in body


def test_nearby_search_uses_account_filtered_coordinates(authenticated_client: TestClient) -> None:
    response = authenticated_client.get(
        "/api/places/nearby",
        params={"latitude": 37.78, "longitude": -122.51, "radius_km": 30},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["scope"] == "all"
    assert body["items"]
    assert all(item["scope"] in {"demo", "personal"} for item in body["items"])


def test_ambiguous_conversational_reference_asks_for_context(authenticated_client: TestClient) -> None:
    response = authenticated_client.post(
        "/api/search",
        json={"query": "show those again", "scope": "demo"},
    )
    assert response.status_code == 200
    assert response.json()["items"] == []
    assert response.json()["evidence_photo_ids"] == []
    assert response.json()["summary"].startswith("Which earlier moment")
