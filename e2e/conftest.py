"""
Shared pytest configuration, HTTP REST client, helper generators, and multi-actor chapter fixtures
for the GDGoC Campus Dashboard Backend Core opaque-box E2E test suite.
"""

from datetime import datetime, timedelta, timezone
import itertools
import os
from pathlib import Path
import sys
import uuid
import pytest
import requests

# Ensure `from conftest import ...` works regardless of pytest working directory
E2E_DIR = Path(__file__).resolve().parent
if str(E2E_DIR) not in sys.path:
    sys.path.insert(0, str(E2E_DIR))

BASE_URL = (
    os.environ.get("E2E_BASE_URL")
    or os.environ.get("GDG_BASE_URL")
    or "http://localhost:18080"
).rstrip("/")
API_V1 = f"{BASE_URL}/api/v1"
RUN_ID = uuid.uuid4().hex[:8]
_SEQ = itertools.count(1)


def next_seq() -> int:
    """Return next monotonically increasing integer sequence for this test run."""
    return next(_SEQ)


def unique_email(prefix: str = "user") -> str:
    """Generate a collision-free email address for E2E test users."""
    clean_prefix = prefix.lower().replace(" ", "_").replace("-", "_")
    return f"{clean_prefix}.{RUN_ID}.{next(_SEQ)}@gdgoc.edu"


def unique_roll(prefix: str = "22CS") -> str:
    """Generate a collision-free student roll number for E2E test users."""
    return f"{prefix}{RUN_ID[:4].upper()}{next(_SEQ):04d}"


def future_iso(days: int = 7, hours: int = 0, minutes: int = 0, seconds: int = 0) -> str:
    """Return an ISO-8601 UTC timestamp in the future."""
    dt = datetime.now(timezone.utc) + timedelta(
        days=days, hours=hours, minutes=minutes, seconds=seconds
    )
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def past_iso(days: int = 7, hours: int = 0, minutes: int = 0, seconds: int = 0) -> str:
    """Return an ISO-8601 UTC timestamp in the past."""
    dt = datetime.now(timezone.utc) - timedelta(
        days=days, hours=hours, minutes=minutes, seconds=seconds
    )
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def auth_headers(actor_or_token) -> dict:
    """Build Authorization Bearer headers from an actor dict or raw JWT token string."""
    if actor_or_token is None:
        return {}
    if isinstance(actor_or_token, dict):
        token = actor_or_token.get("token") or actor_or_token.get("accessToken")
    else:
        token = str(actor_or_token)
    if not token:
        return {}
    return {"Authorization": f"Bearer {token}"}


def unwrap_list(payload, *candidate_keys) -> list:
    """Extract a list from a JSON response that may be either a bare list or a paged/wrapped object."""
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in (
            *candidate_keys,
            "items",
            "content",
            "data",
            "results",
            "records",
            "history",
            "logs",
            "certificates",
            "members",
            "teams",
            "tasks",
            "collaborations",
            "letters",
            "grants",
            "signatures",
            "requests",
        ):
            if key in payload and isinstance(payload[key], list):
                return payload[key]
    return []


def assert_pdf_response(resp: requests.Response) -> None:
    """Verify an HTTP response is a genuine rendered PDF document starting with %PDF-1."""
    assert resp.status_code == 200, (
        f"Expected 200 OK for PDF download, got {resp.status_code}: {resp.text[:300]}"
    )
    content_type = resp.headers.get("Content-Type", "")
    assert "application/pdf" in content_type.lower() or resp.content.startswith(b"%PDF-"), (
        f"Expected application/pdf Content-Type, got '{content_type}'"
    )
    assert resp.content.startswith(b"%PDF-1."), (
        f"Expected PDF binary starting with %PDF-1., got {resp.content[:20]!r}"
    )
    assert len(resp.content) > 100, (
        f"Expected non-trivial PDF binary (>100 bytes), got {len(resp.content)} bytes"
    )


class ApiClient:
    """Thin HTTP REST wrapper around requests.Session for opaque-box E2E tests."""

    def __init__(self, base_url: str = BASE_URL):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()

    def _resolve_url(self, path: str) -> str:
        if path.startswith("http://") or path.startswith("https://"):
            return path
        if not path.startswith("/"):
            path = "/" + path
        return f"{self.base_url}{path}"

    def request(
        self,
        method: str,
        path: str,
        token=None,
        actor=None,
        headers: dict | None = None,
        **kwargs,
    ) -> requests.Response:
        req_headers = {}
        if actor is not None:
            req_headers.update(auth_headers(actor))
        if token is not None:
            req_headers.update(auth_headers(token))
        if headers:
            req_headers.update(headers)
        kwargs.setdefault("timeout", 15)
        url = self._resolve_url(path)
        return self.session.request(method=method, url=url, headers=req_headers, **kwargs)

    def get(self, path: str, token=None, actor=None, **kwargs) -> requests.Response:
        return self.request("GET", path, token=token, actor=actor, **kwargs)

    def post(self, path: str, token=None, actor=None, **kwargs) -> requests.Response:
        return self.request("POST", path, token=token, actor=actor, **kwargs)

    def put(self, path: str, token=None, actor=None, **kwargs) -> requests.Response:
        return self.request("PUT", path, token=token, actor=actor, **kwargs)

    def patch(self, path: str, token=None, actor=None, **kwargs) -> requests.Response:
        return self.request("PATCH", path, token=token, actor=actor, **kwargs)

    def delete(self, path: str, token=None, actor=None, **kwargs) -> requests.Response:
        return self.request("DELETE", path, token=token, actor=actor, **kwargs)


def register_and_login(
    api_client: ApiClient,
    role: str = "TEAM_MEMBER",
    roles: list[str] | None = None,
    full_name: str | None = None,
    department: str = "CSE",
    academic_year: int = 3,
    email: str | None = None,
    password: str = "Password123!",
    phone: str = "+919876543210",
    roll_number: str | None = None,
) -> dict:
    """Register a fresh user via POST /api/v1/auth/register and return actor credentials dict."""
    resolved_roles = list(roles) if roles is not None else [role]
    resolved_email = email or unique_email(resolved_roles[0].lower())
    resolved_roll = roll_number or unique_roll()
    resolved_name = full_name or f"E2E {resolved_roles[0].title().replace('_', ' ')} {next_seq()}"

    payload = {
        "email": resolved_email,
        "password": password,
        "fullName": resolved_name,
        "rollNumber": resolved_roll,
        "department": department,
        "academicYear": academic_year,
        "phone": phone,
        "roles": resolved_roles,
    }
    resp = api_client.post("/api/v1/auth/register", json=payload)
    assert resp.status_code in (200, 201), (
        f"Failed to register {resolved_email} ({resolved_roles}): "
        f"HTTP {resp.status_code} -> {resp.text}"
    )
    body = resp.json()
    token = body.get("accessToken") or body.get("token")
    assert token, f"Missing accessToken in registration response: {body}"
    member = body.get("member") or body

    return {
        "id": member["id"],
        "email": resolved_email,
        "password": password,
        "fullName": member.get("fullName", resolved_name),
        "rollNumber": member.get("rollNumber", resolved_roll),
        "department": member.get("department", department),
        "academicYear": member.get("academicYear", academic_year),
        "roles": member.get("roles", resolved_roles),
        "token": token,
        "headers": {"Authorization": f"Bearer {token}"},
        "member": member,
    }


def create_approved_team(
    api_client: ApiClient,
    gdg_leads: tuple | list,
    name: str | None = None,
    description: str = "Approved E2E Team",
    parent_team_id: int | None = None,
    team_lead: dict | None = None,
    members: list[dict] | None = None,
) -> dict:
    """
    Create a team via the mandatory 3-GDG-Lead consensus workflow (F6) and optionally
    assign a Team Lead and team members.
    """
    assert len(gdg_leads) >= 3, "3 distinct GDG Leads are required for team consensus"
    proposed_name = name or f"Team-{RUN_ID}-{next_seq()}"

    # Vote 1/3: Initiating GDG Lead creates the consensus request
    init_payload = {
        "actionType": "CREATE_TEAM",
        "targetTeamId": None,
        "proposedName": proposed_name,
        "proposedDescription": description,
        "parentTeamId": parent_team_id,
    }
    r1 = api_client.post(
        "/api/v1/teams/consensus-requests",
        token=gdg_leads[0]["token"],
        json=init_payload,
    )
    assert r1.status_code in (200, 201), (
        f"Consensus CREATE_TEAM initiation failed: {r1.status_code} {r1.text}"
    )
    req_data = r1.json()
    req_id = req_data["id"]
    assert req_data.get("status") == "PENDING", f"Expected PENDING after 1st lead, got {req_data}"
    assert req_data.get("approvalCount") == 1, f"Expected approvalCount=1, got {req_data}"

    # Vote 2/3: Second GDG Lead votes approve
    r2 = api_client.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        token=gdg_leads[1]["token"],
        json={"approved": True, "comment": "Approved by GDG Lead 2"},
    )
    assert r2.status_code == 200, f"2nd GDG Lead vote failed: {r2.status_code} {r2.text}"
    r2_data = r2.json()
    assert r2_data.get("status") == "PENDING", f"Expected PENDING after 2nd lead, got {r2_data}"
    assert r2_data.get("approvalCount") == 2, f"Expected approvalCount=2, got {r2_data}"

    # Vote 3/3: Third GDG Lead votes approve -> triggers atomic execution
    r3 = api_client.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        token=gdg_leads[2]["token"],
        json={"approved": True, "comment": "Approved by GDG Lead 3"},
    )
    assert r3.status_code == 200, f"3rd GDG Lead vote failed: {r3.status_code} {r3.text}"
    r3_data = r3.json()
    assert r3_data.get("status") == "EXECUTED", f"Expected EXECUTED after 3rd lead, got {r3_data}"
    assert r3_data.get("approvalCount") == 3, f"Expected approvalCount=3, got {r3_data}"
    team_id = r3_data.get("executedTeamId") or r3_data.get("targetTeamId")
    assert team_id is not None, f"Expected executedTeamId in {r3_data}"

    # Assign Team Lead if provided
    if team_lead is not None:
        tl_resp = api_client.post(
            f"/api/v1/teams/{team_id}/members",
            token=gdg_leads[0]["token"],
            json={"memberId": team_lead["id"], "teamRole": "TEAM_LEAD"},
        )
        assert tl_resp.status_code in (200, 201), (
            f"Assigning TEAM_LEAD to team {team_id} failed: {tl_resp.status_code} {tl_resp.text}"
        )

    # Assign Members if provided
    if members:
        for member in members:
            m_resp = api_client.post(
                f"/api/v1/teams/{team_id}/members",
                token=gdg_leads[0]["token"],
                json={"memberId": member["id"], "teamRole": "MEMBER"},
            )
            assert m_resp.status_code in (200, 201), (
                f"Assigning MEMBER {member['id']} to team {team_id} failed: "
                f"{m_resp.status_code} {m_resp.text}"
            )

    team_resp = api_client.get(f"/api/v1/teams/{team_id}", token=gdg_leads[0]["token"])
    team_body = team_resp.json() if team_resp.status_code == 200 else {}
    return {
        **team_body,
        "id": team_id,
        "name": team_body.get("name", proposed_name),
        "description": team_body.get("description", description),
        "parentTeamId": team_body.get("parentTeamId", parent_team_id),
        "consensusRequestId": req_id,
    }


# ---------------------------------------------------------------------------
# Pytest Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def base_url() -> str:
    return BASE_URL


@pytest.fixture(scope="session")
def api(base_url: str) -> ApiClient:
    return ApiClient(base_url)


@pytest.fixture(scope="function")
def user_factory(api: ApiClient):
    """Callable factory to register & login a fresh user with custom role(s) on demand."""

    def _create_user(role: str = "TEAM_MEMBER", **kwargs) -> dict:
        return register_and_login(api, role=role, **kwargs)

    return _create_user


@pytest.fixture(scope="function")
def gdg_lead_1(api: ApiClient) -> dict:
    return register_and_login(api, role="GDG_LEAD", full_name="GDG Lead One")


@pytest.fixture(scope="function")
def gdg_lead_2(api: ApiClient) -> dict:
    return register_and_login(api, role="GDG_LEAD", full_name="GDG Lead Two")


@pytest.fixture(scope="function")
def gdg_lead_3(api: ApiClient) -> dict:
    return register_and_login(api, role="GDG_LEAD", full_name="GDG Lead Three")


@pytest.fixture(scope="function")
def gdg_leads(gdg_lead_1: dict, gdg_lead_2: dict, gdg_lead_3: dict) -> tuple[dict, dict, dict]:
    return (gdg_lead_1, gdg_lead_2, gdg_lead_3)


@pytest.fixture(scope="function")
def hr_lead(api: ApiClient) -> dict:
    return register_and_login(api, role="HR_LEAD", full_name="HR Lead")


@pytest.fixture(scope="function")
def team_lead_a(api: ApiClient) -> dict:
    return register_and_login(api, role="TEAM_LEAD", full_name="Team Lead Alpha")


@pytest.fixture(scope="function")
def team_lead_b(api: ApiClient) -> dict:
    return register_and_login(api, role="TEAM_LEAD", full_name="Team Lead Beta")


@pytest.fixture(scope="function")
def event_lead(api: ApiClient) -> dict:
    return register_and_login(api, role="EVENT_LEAD", full_name="Event Lead")


@pytest.fixture(scope="function")
def hr_member(api: ApiClient) -> dict:
    return register_and_login(api, role="HR_MEMBER", full_name="HR Team Member")


@pytest.fixture(scope="function")
def event_team_member(api: ApiClient) -> dict:
    return register_and_login(api, role="EVENT_TEAM_MEMBER", full_name="Event Team Member")


@pytest.fixture(scope="function")
def team_member_a(api: ApiClient) -> dict:
    return register_and_login(api, role="TEAM_MEMBER", full_name="Team Member Alpha")


@pytest.fixture(scope="function")
def team_member_b(api: ApiClient) -> dict:
    return register_and_login(api, role="TEAM_MEMBER", full_name="Team Member Beta")


@pytest.fixture(scope="function")
def team_a(
    api: ApiClient,
    gdg_leads: tuple[dict, dict, dict],
    team_lead_a: dict,
    team_member_a: dict,
) -> dict:
    return create_approved_team(
        api,
        gdg_leads,
        name=f"Alpha-Team-{RUN_ID}-{next_seq()}",
        description="Primary Alpha technical team",
        team_lead=team_lead_a,
        members=[team_member_a],
    )


@pytest.fixture(scope="function")
def team_b(
    api: ApiClient,
    gdg_leads: tuple[dict, dict, dict],
    team_lead_b: dict,
    team_member_b: dict,
) -> dict:
    return create_approved_team(
        api,
        gdg_leads,
        name=f"Beta-Team-{RUN_ID}-{next_seq()}",
        description="Secondary Beta cloud team",
        team_lead=team_lead_b,
        members=[team_member_b],
    )
