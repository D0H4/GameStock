"""GameStock OpenAPI client; Python 3.10+, standard library only.

The command line only reads data. Use the explicit trading methods from your own
program, persisting a unique request_id before sending each new order. Retries
must use that same ID and body. Credentials are read from process environment,
never from a .env file.
"""

import argparse
import json
import os
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class ApiError(RuntimeError):
    def __init__(self, status, body, retry_after=None):
        self.status = status
        self.body = body
        self.retry_after = retry_after
        super().__init__(f"HTTP {status}: {body.get('message', 'API request failed')}")


class _NoRedirect(HTTPRedirectHandler):
    # Never forward a trading credential or change a write into a GET on redirect.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GameStockClient:
    def __init__(self, base_url="http://localhost:8081", api_key=None, timeout=20):
        address = urlsplit(base_url)
        if (address.scheme not in ("http", "https") or not address.netloc or address.username or address.password
                or address.path not in ("", "/") or address.query or address.fragment):
            raise ValueError("Use an HTTP(S) API origin without embedded credentials")
        if address.scheme != "https" and address.hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("Use HTTPS for a remote API server")
        self.base_url = base_url.rstrip("/") + "/api/openapi/v1"
        self.api_key = api_key
        self.timeout = timeout
        self._opener = build_opener(_NoRedirect())

    def _request(self, method, path, body=None, *, private=False, request_id=None):
        headers = {"Accept": "application/json"}
        if private:
            if not self.api_key:
                raise ValueError("An API key is required for account and trading requests")
            headers["Authorization"] = "Bearer " + self.api_key
        if method != "GET":
            if not request_id:
                raise ValueError("Persist a request_id before sending a trade")
            headers["Idempotency-Key"] = request_id
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, allow_nan=False).encode("utf-8")
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                return json.load(response)
        except HTTPError as error:
            try:
                details = json.load(error)
            except (ValueError, UnicodeError):
                details = {"message": "Non-JSON API response"}
            if not isinstance(details, dict):
                details = {"message": "Unexpected API error response"}
            raise ApiError(error.code, details, error.headers.get("Retry-After")) from None

    def quotes(self):
        return self._request("GET", "/quotes")

    def options(self, cycle_id=None, option_type=None):
        query = {key: value for key, value in {"cycleId": cycle_id, "type": option_type}.items() if value is not None}
        return self._request("GET", "/options" + ("?" + urlencode(query) if query else ""))

    def account(self):
        return self._request("GET", "/account", private=True)

    def order_status(self, order_id):
        return self._request("GET", f"/orders/{int(order_id)}", private=True)

    def open_orders(self, symbol=None, limit=50, before_id=None):
        query = {key: value for key, value in {"symbol": symbol, "limit": limit, "beforeId": before_id}.items() if value is not None}
        return self._request("GET", "/orders?" + urlencode(query), private=True)

    def submit_order(self, order, *, request_id):
        return self._request("POST", "/orders", order, private=True, request_id=request_id)

    def close_position(self, position_id, quantity, *, request_id):
        return self._request("POST", f"/positions/{int(position_id)}/close", {"quantity": quantity},
                             private=True, request_id=request_id)

    def cancel_order(self, order_id, *, request_id):
        return self._request("DELETE", f"/orders/{int(order_id)}", private=True, request_id=request_id)

    def receipt(self, request_id):
        return self._request("GET", "/requests/" + quote(request_id, safe=""), private=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("command", choices=("quotes", "options", "account"), default="quotes", nargs="?")
    args = parser.parse_args()
    client = GameStockClient(args.base_url, os.environ.get("GAMESTOCK_API_KEY"))
    print(json.dumps(getattr(client, args.command)(), ensure_ascii=False, indent=2))
