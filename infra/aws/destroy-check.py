#!/usr/bin/env python3
"""Read-only audit of tagged billable resources after terraform destroy."""
import json
from pathlib import Path
import subprocess
import sys

PROFILE = "movie-loadtest"
REGION = "ap-northeast-2"
ACCOUNT = "976071095824"


def aws(*args):
    command = ["aws", *args, "--profile", PROFILE, "--region", REGION, "--output", "json"]
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def main(output):
    account = aws("sts", "get-caller-identity")["Account"]
    if account != ACCOUNT:
        raise SystemExit(f"Unexpected AWS account: {account}")
    tagged = aws("resourcegroupstaggingapi", "get-resources", "--tag-filters", "Key=project,Values=movie-loadtest")
    instances = aws("ec2", "describe-instances", "--filters",
                    "Name=tag:project,Values=movie-loadtest",
                    "Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down")
    volumes = aws("ec2", "describe-volumes", "--filters", "Name=tag:project,Values=movie-loadtest")
    addresses = aws("ec2", "describe-addresses", "--filters", "Name=tag:project,Values=movie-loadtest")
    gateways = aws("ec2", "describe-nat-gateways", "--filter", "Name=tag:project,Values=movie-loadtest")
    result = {
        "account": account,
        "resourceArns": sorted(item["ResourceARN"] for item in tagged.get("ResourceTagMappingList", [])),
        "instances": sorted(item["InstanceId"] for reservation in instances.get("Reservations", [])
                            for item in reservation.get("Instances", [])),
        "volumes": sorted(item["VolumeId"] for item in volumes.get("Volumes", [])),
        "elasticIps": sorted(item.get("AllocationId", item.get("PublicIp")) for item in addresses.get("Addresses", [])),
        "natGateways": sorted(item["NatGatewayId"] for item in gateways.get("NatGateways", [])
                              if item.get("State") != "deleted"),
    }
    Path(output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: len(value) for key, value in result.items() if isinstance(value, list)}, sort_keys=True))
    if any(value for value in result.values() if isinstance(value, list)):
        raise SystemExit("Tagged resources remain after destroy")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: destroy-check.py <output.json>")
    main(sys.argv[1])
