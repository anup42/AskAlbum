from __future__ import annotations

import argparse
import csv
import sys

from sqlalchemy.dialects.postgresql import insert as postgres_insert

from .database import SessionLocal
from .models import PlaceGazetteer


def rows(source):
    reader = csv.DictReader(source)
    required = {"name", "latitude", "longitude"}
    if not reader.fieldnames or not required.issubset(reader.fieldnames):
        raise ValueError("CSV requires name, latitude and longitude columns")
    for row in reader:
        yield {
            "name": row["name"].strip()[:250],
            "country_code": (row.get("country_code") or "").strip().upper()[:2] or None,
            "latitude": float(row["latitude"]),
            "longitude": float(row["longitude"]),
            "population": int(row.get("population") or 0),
        }


def main() -> int:
    parser = argparse.ArgumentParser(description="Import an offline place gazetteer CSV.")
    parser.add_argument("csv_file", help="CSV path, or - for standard input")
    args = parser.parse_args()
    source = sys.stdin if args.csv_file == "-" else open(args.csv_file, newline="", encoding="utf-8")
    count = 0
    try:
        with SessionLocal() as db:
            for payload in rows(source):
                if not payload["name"] or not -90 <= payload["latitude"] <= 90 or not -180 <= payload["longitude"] <= 180:
                    continue
                if db.get_bind().dialect.name == "postgresql":
                    statement = (
                        postgres_insert(PlaceGazetteer)
                        .values(**payload)
                        .on_conflict_do_nothing(
                            index_elements=["name", "country_code", "latitude", "longitude"]
                        )
                    )
                    db.execute(statement)
                else:
                    db.add(PlaceGazetteer(**payload))
                count += 1
                if count % 5000 == 0:
                    db.commit()
            db.commit()
    finally:
        if source is not sys.stdin:
            source.close()
    print(f"Imported or retained {count} place rows.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
