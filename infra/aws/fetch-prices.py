#!/usr/bin/env python3
"""Save the AWS Price List JSON used for the one-day measurement estimate."""
import json
from datetime import datetime, timezone
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
LOCATION = "Asia Pacific (Seoul)"
TYPES = ("c7g.xlarge", "c7g.large", "m7g.xlarge", "r7g.large", "r7g.xlarge", "t4g.medium")


def products(*filters):
    arguments = ["aws", "pricing", "get-products", "--profile", "movie-loadtest",
                 "--region", "us-east-1", "--service-code", "AmazonEC2", "--max-results", "100", "--output", "json"]
    arguments += ["--filters"] + [f"Type=TERM_MATCH,Field={field},Value={value}" for field, value in filters]
    result = subprocess.run(arguments, capture_output=True, text=True, check=True)
    return [json.loads(item) for item in json.loads(result.stdout).get("PriceList", [])]


if __name__ == "__main__":
    data = {"fetchedAt": datetime.now(timezone.utc).isoformat(),
            "source": "AWS Price List GetProducts API", "location": LOCATION, "instances": {}, "gp3": []}
    for instance in TYPES:
        items = products(("instanceType", instance), ("location", LOCATION),
                         ("operatingSystem", "Linux"), ("tenancy", "Shared"),
                         ("preInstalledSw", "NA"), ("capacitystatus", "Used"))
        if len(items) != 1:
            raise SystemExit(f"Expected one price for {instance}, got {len(items)}")
        data["instances"][instance] = items[0]
    data["gp3"] = products(("volumeApiName", "gp3"), ("location", LOCATION))
    output = HERE / "pricing-2026-11-08.json"
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print(output)
