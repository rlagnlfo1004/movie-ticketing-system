#!/usr/bin/env python3
"""Open the demo with public PortOne identifiers from the local config file."""

import argparse
from pathlib import Path
from urllib.parse import urlencode, urlsplit, urlunsplit, parse_qsl
import webbrowser


def public_identifiers(path: Path) -> dict[str, str]:
    wanted = {"PORTONE_STORE_ID", "PORTONE_CHANNEL_KEY"}
    found = {}
    for line in path.read_text().splitlines():
        key, separator, value = line.partition("=")
        key = key.strip().removeprefix("export ").strip()
        if separator and key in wanted:
            found[key] = value.strip().strip('"\'')
    missing = wanted - found.keys()
    if missing:
        raise SystemExit(f"Missing public PortOne fields: {', '.join(sorted(missing))}")
    return found


parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://localhost:8000/demo/")
args = parser.parse_args()
config = public_identifiers(Path.home() / ".config/movie-ticketing/portone.env")
parts = urlsplit(args.base_url)
query = dict(parse_qsl(parts.query))
query.update({"mode": "live", "storeId": config["PORTONE_STORE_ID"], "channelKey": config["PORTONE_CHANNEL_KEY"]})
webbrowser.open(urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment)))
