from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


FIXTURES = {
    "synthetic_swiggy_receipt": [
        "SYNTHETIC TEST RECEIPT", "SWIGGY TEST KITCHEN", "18 JUL 2026", "Order TEST-1842",
        "Subtotal       INR 1,180", "Tax               INR 118", "Discount          INR 50",
        "GRAND TOTAL     INR 1,248", "Amount Paid      INR 1,248",
    ],
    "synthetic_restaurant_receipt": [
        "SYNTHETIC TEST RECEIPT", "FICUS CAFE", "12 MAR 2024", "Subtotal         INR 900",
        "Tax               INR 90", "Discount          INR 40", "GRAND TOTAL     INR 950",
    ],
    "synthetic_wifi_card": [
        "SYNTHETIC WI-FI TEST CARD", "Network: GalleryDemo", "Password: mango-tree-2048",
        "This credential is fictitious.",
    ],
    "synthetic_boarding_pass": [
        "SYNTHETIC BOARDING PASS", "Passenger: TEST TRAVELLER", "Flight: AG 204",
        "DEL -> SIN", "Date: 12 MAR 2024", "Departure: 08:10", "Gate: T04",
    ],
    "synthetic_hotel_confirmation": [
        "SYNTHETIC HOTEL CONFIRMATION", "Marina Test Hotel, Singapore",
        "Check-in: 12 MAR 2024", "Check-out: 18 MAR 2024", "Booking: TEST-SG-1203",
    ],
    "synthetic_menu_english": [
        "SYNTHETIC TEST MENU", "Coconut Curry  INR 320", "Garden Rice  INR 180", "Lime Soda  INR 90",
    ],
    "synthetic_menu_transliterated": [
        "SYNTHETIC TEST MENU", "Masala chai  INR 80", "Aloo tikki  INR 140", "Nimbu pani  INR 70",
    ],
    "synthetic_calendar": [
        "SYNTHETIC CALENDAR", "Singapore trip", "12-18 March 2024", "Marina Bay on 13 March",
    ],
}


def _font(size: int, bold: bool = False) -> ImageFont.ImageFont:
    candidates = [
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def render_document(path: Path, lines: list[str], accent: tuple[int, int, int] = (23, 63, 53)) -> None:
    image = Image.new("RGB", (1240, 1754), "white")
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((70, 70, 1170, 1684), radius=22, outline=(190, 200, 194), width=4)
    draw.rectangle((70, 70, 1170, 210), fill=accent)
    y = 112
    for index, line in enumerate(lines):
        font = _font(38 if index else 44, bold=index == 0 or "TOTAL" in line or "Password" in line)
        fill = "white" if index == 0 else (20, 32, 29)
        if index == 1:
            y = 265
        draw.text((115, y), line, font=font, fill=fill)
        y += 82 if index else 120
    draw.text((115, 1580), "CC0 synthetic fixture - not a real credential or transaction", font=_font(24), fill=(90, 100, 95))
    image.save(path, "PNG", optimize=True)


def render_people_fixture(path: Path) -> None:
    image = Image.new("RGB", (1200, 900), (225, 235, 245))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 650, 1200, 900), fill=(110, 155, 105))
    people = [(360, "Person A", (235, 203, 52), (184, 82, 61)), (820, "Person B", (55, 80, 150), (45, 75, 145))]
    for x, label, hat, suit in people:
        draw.ellipse((x - 90, 170, x + 90, 350), fill=(174, 122, 86), outline=(45, 45, 45), width=5)
        draw.rectangle((x - 125, 330, x + 125, 720), fill=suit, outline=(45, 45, 45), width=6)
        if label == "Person A":
            draw.rectangle((x - 115, 145, x + 115, 195), fill=hat, outline=(45, 45, 45), width=5)
            draw.ellipse((x - 82, 95, x + 82, 205), fill=hat, outline=(45, 45, 45), width=5)
        draw.text((x - 105, 755), label, font=_font(34, bold=True), fill=(20, 32, 29))
    draw.text((210, 35), "SYNTHETIC PEOPLE RELATION FIXTURE", font=_font(42, bold=True), fill=(23, 63, 53))
    image.save(path, "PNG", optimize=True)


def generate(output: Path) -> list[dict[str, object]]:
    output.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, object]] = []
    for fixture_id, lines in FIXTURES.items():
        path = output / f"{fixture_id}.png"
        render_document(path, lines)
        records.append({"id": fixture_id, "filename": path.name, "kind": "IMAGE", "labels": ["synthetic", "document", fixture_id], "ocr_ground_truth": "\n".join(lines)})
    people_path = output / "synthetic_people_relation.png"
    render_people_fixture(people_path)
    records.append({"id": "synthetic_people_relation", "filename": people_path.name, "kind": "IMAGE", "labels": ["synthetic", "people", "fixture_person_a", "fixture_person_b", "yellow_hat", "blue_suit"]})
    pdf_pages = [output / "synthetic_pdf_page_1.png", output / "synthetic_pdf_page_2.png"]
    render_document(pdf_pages[0], ["SYNTHETIC TWO-PAGE PDF", "Page 1", "Project: Agentic Gallery", "Reference: PDF-TEST-204"])
    render_document(pdf_pages[1], ["SYNTHETIC TWO-PAGE PDF", "Page 2", "Known fact: evidence stays on device", "Date: 21 JUL 2026"])
    images = [Image.open(page).convert("RGB") for page in pdf_pages]
    pdf_path = output / "synthetic_two_page_document.pdf"
    images[0].save(pdf_path, save_all=True, append_images=images[1:])
    for image in images:
        image.close()
    records.append({"id": "synthetic_two_page_document", "filename": pdf_path.name, "kind": "PDF", "labels": ["synthetic", "document", "pdf"], "page_count": 2})
    return records


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    records = generate(args.output)
    (args.output / "synthetic-manifest.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    print(f"Generated {len(records)} CC0 synthetic fixtures in {args.output}")


if __name__ == "__main__":
    main()
