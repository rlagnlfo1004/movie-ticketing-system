#!/usr/bin/env python3
"""Calculate the hourly estimate from the saved AWS Price List response."""
import json
from pathlib import Path

data = json.loads((Path(__file__).parent / "pricing-2026-11-08.json").read_text())


def usd_for_unit(product, unit):
    values = [float(dimension["pricePerUnit"]["USD"])
              for term in product["terms"]["OnDemand"].values()
              for dimension in term["priceDimensions"].values()
              if dimension.get("unit") == unit]
    if len(values) != 1:
        raise ValueError(f"Expected one {unit} price, got {values}")
    return values[0]


hosts = {
    "lg": "c7g.xlarge", "edge": "c7g.large", "app-waiting": "m7g.xlarge",
    "app-reservation": "m7g.xlarge", "redis": "r7g.large", "mysql": "r7g.xlarge",
    "mon": "t4g.medium",
}
instance_prices = {host: usd_for_unit(data["instances"][kind], "Hrs") for host, kind in hosts.items()}
gp3_product = next(item for item in data["gp3"] if item["product"]["attributes"]["usagetype"].endswith("VolumeUsage.gp3"))
storage = 240 * usd_for_unit(gp3_product, "GB-Mo") / 730
public_ipv4 = 7 * 0.005
print(json.dumps({"ec2": round(sum(instance_prices.values()), 6),
                  "gp3": round(storage, 6), "publicIpv4": round(public_ipv4, 6),
                  "total": round(sum(instance_prices.values()) + storage + public_ipv4, 6),
                  "instances": instance_prices}, indent=2))
