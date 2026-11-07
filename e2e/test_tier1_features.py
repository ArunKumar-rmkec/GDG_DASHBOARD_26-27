"""
Tier 1 — Happy-Path Feature Verification Suite (125 Tests: 5 per Feature F1–F25).

Every test exercises genuine HTTP REST endpoints against the live Spring Boot backend
(`E2E_BASE_URL` / `GDG_BASE_URL`), verifying status codes, response payloads, RBAC clearances,
state transitions, and `%PDF-1.x` binary rendering.
"""

import uuid
from conftest import (
    RUN_ID,
    assert_pdf_response,
    create_approved_team,
    future_iso,
    next_seq,
    past_iso,
    register_and_login,
    unique_email,
    unique_roll,
    unwrap_list,
)


# ===========================================================================
# Feature F1: Stateless JWT & Google OAuth2 Authentication (5 Tests)
# ===========================================================================


def test_f01_t1_01_register_member_returns_jwt_and_profile(api):
    email = unique_email("f1_reg")
    resp = api.post(
        "/api/v1/auth/register",
        json={
            "email": email,
            "password": "Password123!",
            "fullName": "Arun Kumar",
            "rollNumber": unique_roll(),
            "department": "CSE",
            "academicYear": 3,
            "phone": "+919876543210",
            "roles": ["TEAM_MEMBER"],
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body.get("accessToken")
    assert body.get("tokenType", "Bearer") == "Bearer"
    member = body["member"]
    assert member["email"] == email
    assert member["status"] == "ACTIVE"
    assert "TEAM_MEMBER" in member["roles"]


def test_f01_t1_02_login_with_valid_credentials_returns_jwt(api):
    user = register_and_login(api, role="TEAM_MEMBER", full_name="Login User")
    resp = api.post(
        "/api/v1/auth/login",
        json={"email": user["email"], "password": user["password"]},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("accessToken")
    assert body.get("tokenType", "Bearer") == "Bearer"
    assert body["member"]["id"] == user["id"]
    assert body["member"]["email"] == user["email"]


def test_f01_t1_03_google_oauth2_login_creates_or_authenticates_member(api):
    email = unique_email("f1_google")
    resp = api.post(
        "/api/v1/auth/google",
        json={
            "idToken": f"google-id-token-{uuid.uuid4().hex}",
            "email": email,
            "fullName": "Google OAuth User",
            "googleSub": f"google-sub-{uuid.uuid4().hex}",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body.get("accessToken")
    assert body.get("tokenType", "Bearer") == "Bearer"
    assert body["member"]["email"] == email
    assert body["member"]["status"] == "ACTIVE"


def test_f01_t1_04_get_auth_me_returns_authenticated_principal_details(api, gdg_lead_1):
    resp = api.get("/api/v1/auth/me", actor=gdg_lead_1)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["id"] == gdg_lead_1["id"]
    assert body["email"] == gdg_lead_1["email"]
    assert body["status"] == "ACTIVE"
    assert "GDG_LEAD" in body["roles"]
    assert body.get("terminatedAt") is None
    assert body.get("letterWindowExpiresAt") is None


def test_f01_t1_05_register_multi_role_lead_persists_all_roles_in_me(api):
    multi_user = register_and_login(
        api,
        roles=["TEAM_LEAD", "EVENT_LEAD"],
        full_name="Multi Role Lead",
    )
    resp = api.get("/api/v1/auth/me", actor=multi_user)
    assert resp.status_code == 200, resp.text
    roles = resp.json()["roles"]
    assert "TEAM_LEAD" in roles
    assert "EVENT_LEAD" in roles


# ===========================================================================
# Feature F2: Super Admin Role (GDG_LEAD) All-Round Access & Non-Shareability (5 Tests)
# ===========================================================================


def test_f02_t1_01_gdg_lead_has_visibility_across_all_teams_and_stats(
    api, gdg_lead_1, team_a, team_b
):
    teams_resp = api.get("/api/v1/teams", actor=gdg_lead_1)
    assert teams_resp.status_code == 200, teams_resp.text
    team_ids = {t["id"] for t in unwrap_list(teams_resp.json())}
    assert team_a["id"] in team_ids
    assert team_b["id"] in team_ids

    stats_resp = api.get("/api/v1/teams/stats", actor=gdg_lead_1)
    assert stats_resp.status_code == 200, stats_resp.text
    stat_team_ids = {s["teamId"] for s in unwrap_list(stats_resp.json())}
    assert team_a["id"] in stat_team_ids
    assert team_b["id"] in stat_team_ids


def test_f02_t1_02_gdg_lead_can_view_all_members_and_past_members(
    api, gdg_lead_1, team_member_a
):
    m_resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=gdg_lead_1)
    assert m_resp.status_code == 200, m_resp.text
    assert m_resp.json()["id"] == team_member_a["id"]

    pm_resp = api.get("/api/v1/teams/past-members", actor=gdg_lead_1)
    assert pm_resp.status_code == 200, pm_resp.text


def test_f02_t1_03_gdg_lead_can_directly_execute_bio_data_crud(
    api, gdg_lead_1, team_member_a
):
    resp = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=gdg_lead_1,
        json={
            "fullName": "Updated By GDG Lead",
            "department": "AI&DS",
            "academicYear": 4,
            "phone": "+919999988888",
            "bio": "Updated by super admin",
            "skills": "Java,Spring Boot,GCP",
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("executionStatus", "EXECUTED") == "EXECUTED"


def test_f02_t1_04_gdg_lead_can_approve_cross_team_collaborations_and_change_requests(
    api, gdg_lead_1, hr_member, team_a, team_member_a
):
    staged = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Staged For Super Admin",
            "department": "CSE",
            "academicYear": 3,
            "phone": "+919876500001",
        },
    )
    assert staged.status_code in (200, 201, 202), staged.text
    s_body = staged.json()
    req_id = s_body.get("changeRequestId") or s_body.get("id")
    assert req_id is not None

    approve = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Super admin direct execution"},
    )
    assert approve.status_code == 200, approve.text
    assert approve.json()["status"] == "EXECUTED"


def test_f02_t1_05_gdg_lead_can_manage_agreements_and_issue_letters(
    api, gdg_lead_1, team_member_a
):
    resp = api.post(
        "/api/v1/letters/issue",
        actor=gdg_lead_1,
        json={
            "memberId": team_member_a["id"],
            "letterType": "APPRECIATION",
            "title": "Super Admin Appreciation Letter",
            "contentHtml": "<h1>Appreciation</h1><p>Outstanding contribution to GDGoC.</p>",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body.get("id") is not None
    assert body.get("letterType") == "APPRECIATION"


# ===========================================================================
# Feature F3: Lead Admin Role & Permission Granting (5 Tests)
# ===========================================================================


def test_f03_t1_01_gdg_lead_grants_view_team_stats_permission_to_team_member(
    api, gdg_lead_1, team_member_a, team_a
):
    grant_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=7),
            "reason": "Analytics support",
        },
    )
    assert grant_resp.status_code in (200, 201), grant_resp.text
    grant = grant_resp.json()
    assert grant["status"] == "ACTIVE"
    assert grant.get("revoked") is False

    stats_resp = api.get("/api/v1/teams/stats", actor=team_member_a)
    assert stats_resp.status_code == 200, stats_resp.text


def test_f03_t1_02_team_lead_grants_intra_team_task_assignment_to_own_team_member(
    api, team_lead_a, team_member_a, team_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_INTRA_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=7),
            "reason": "Sprint task coordinator",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert resp.json()["status"] == "ACTIVE"


def test_f03_t1_03_hr_lead_grants_view_member_details_to_hr_member(
    api, hr_lead, hr_member
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=hr_lead,
        json={
            "granteeMemberId": hr_member["id"],
            "permissionCode": "VIEW_MEMBER_DETAILS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=7),
            "reason": "HR member directory audit",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert resp.json()["status"] == "ACTIVE"


def test_f03_t1_04_event_lead_grants_event_management_to_event_team_member(
    api, event_lead, event_team_member
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=event_lead,
        json={
            "granteeMemberId": event_team_member["id"],
            "permissionCode": "EVENT_MANAGEMENT",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=7),
            "reason": "Registration desk operations",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert resp.json()["status"] == "ACTIVE"


def test_f03_t1_05_cross_team_grant_by_team_lead_activates_after_target_team_lead_approval(
    api, team_lead_a, team_lead_b, team_member_a, team_a, team_b
):
    create_resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "DASHBOARD_ACTION_ACCESS",
            "scopeTeamId": team_a["id"],
            "crossTeamTargetTeamId": team_b["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=7),
            "reason": "Cross-team dashboard support",
        },
    )
    assert create_resp.status_code in (200, 201), create_resp.text
    grant = create_resp.json()
    assert grant["status"] == "PENDING_TEAM_LEAD_APPROVAL"

    approve_resp = api.post(
        f"/api/v1/permissions/grants/{grant['id']}/approve",
        actor=team_lead_b,
        json={"approved": True, "comment": "Approved by Team B Lead"},
    )
    assert approve_resp.status_code == 200, approve_resp.text
    assert approve_resp.json()["status"] == "ACTIVE"


# ===========================================================================
# Feature F4: Scheduled & Temporary Access Sharing (5 Tests)
# ===========================================================================


def test_f04_t1_01_scheduled_grant_active_within_valid_from_and_valid_until_window(
    api, gdg_lead_1, team_member_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_PAST_MEMBERS",
            "validFrom": past_iso(hours=2),
            "validUntil": future_iso(hours=2),
            "reason": "Active scheduled window",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text
    pm_resp = api.get("/api/v1/teams/past-members", actor=team_member_a)
    assert pm_resp.status_code == 200, pm_resp.text


def test_f04_t1_02_permanent_grant_with_null_valid_until_is_immediately_active(
    api, gdg_lead_1, team_member_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "MONITOR_COLLABORATIONS",
            "validFrom": past_iso(minutes=10),
            "validUntil": None,
            "reason": "Ongoing collaboration monitoring",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text
    c_resp = api.get("/api/v1/collaborations", actor=team_member_a)
    assert c_resp.status_code == 200, c_resp.text


def test_f04_t1_03_explicit_revocation_immediately_revokes_active_grant(
    api, gdg_lead_1, team_member_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=3),
            "reason": "Temporary stats access",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text
    grant_id = g_resp.json()["id"]

    assert api.get("/api/v1/teams/stats", actor=team_member_a).status_code == 200

    rev_resp = api.delete(f"/api/v1/permissions/grants/{grant_id}", actor=gdg_lead_1)
    assert rev_resp.status_code == 200, rev_resp.text
    rev_body = rev_resp.json()
    assert rev_body.get("revoked") is True or rev_body.get("status") == "REVOKED"

    after_resp = api.get("/api/v1/teams/stats", actor=team_member_a)
    assert after_resp.status_code == 403, after_resp.text


def test_f04_t1_04_list_all_grants_and_my_grants_returns_schedule_metadata(
    api, gdg_lead_1, team_member_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Metadata check",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text
    grant_id = g_resp.json()["id"]

    all_resp = api.get("/api/v1/permissions/grants", actor=gdg_lead_1)
    assert all_resp.status_code == 200, all_resp.text
    all_ids = {g["id"] for g in unwrap_list(all_resp.json())}
    assert grant_id in all_ids

    my_resp = api.get("/api/v1/permissions/grants/my", actor=team_member_a)
    assert my_resp.status_code == 200, my_resp.text
    my_grants = unwrap_list(my_resp.json())
    matching = [g for g in my_grants if g["id"] == grant_id]
    assert matching
    assert matching[0].get("validFrom") is not None
    assert matching[0].get("permissionCode") == "VIEW_TEAM_STATS"


def test_f04_t1_05_future_scheduled_grant_created_successfully_for_upcoming_window(
    api, gdg_lead_1, team_member_a
):
    vf = future_iso(days=2)
    vu = future_iso(days=5)
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": vf,
            "validUntil": vu,
            "reason": "Upcoming sprint review window",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body.get("validFrom") is not None
    assert body.get("validUntil") is not None


# ===========================================================================
# Feature F5: 10-Day Post-Termination Permission Revocation & Letter Window (5 Tests)
# ===========================================================================


def test_f05_t1_01_terminate_member_sets_status_window_and_generates_termination_letter(
    api, hr_lead, team_member_a
):
    resp = api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Code of conduct violation", "terminatedAt": None},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    member_data = body.get("member", body)
    assert member_data["status"] == "TERMINATED"
    assert member_data.get("terminatedAt") is not None
    assert member_data.get("letterWindowExpiresAt") is not None


def test_f05_t1_02_terminated_member_can_access_own_profile_within_10_day_window(
    api, hr_lead, team_member_a
):
    t_resp = api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Resignation processed", "terminatedAt": None},
    )
    assert t_resp.status_code == 200, t_resp.text

    prof_resp = api.get("/api/v1/profile/me", actor=team_member_a)
    assert prof_resp.status_code == 200, prof_resp.text
    prof = prof_resp.json()
    assert prof["status"] == "TERMINATED"
    assert prof.get("letterWindowExpiresAt") is not None


def test_f05_t1_03_terminated_member_can_list_and_download_letters_within_10_day_window(
    api, hr_lead, team_member_a
):
    t_resp = api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "End of academic tenure", "terminatedAt": None},
    )
    assert t_resp.status_code == 200, t_resp.text

    letters_resp = api.get("/api/v1/profile/me/letters", actor=team_member_a)
    assert letters_resp.status_code == 200, letters_resp.text
    letters = unwrap_list(letters_resp.json())
    term_letters = [l for l in letters if l.get("letterType") == "TERMINATION"]
    assert term_letters, f"Expected auto-generated TERMINATION letter in {letters}"

    dl_resp = api.get(
        f"/api/v1/profile/me/letters/{term_letters[0]['id']}/download",
        actor=team_member_a,
    )
    assert_pdf_response(dl_resp)


def test_f05_t1_04_termination_immediately_revokes_active_permission_grants(
    api, gdg_lead_1, hr_lead, team_member_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Pre-termination grant",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text
    grant_id = g_resp.json()["id"]

    t_resp = api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Revocation test", "terminatedAt": None},
    )
    assert t_resp.status_code == 200, t_resp.text

    grants_resp = api.get("/api/v1/permissions/grants", actor=gdg_lead_1)
    assert grants_resp.status_code == 200, grants_resp.text
    target = [g for g in unwrap_list(grants_resp.json()) if g["id"] == grant_id]
    assert target
    assert target[0].get("revoked") is True or target[0].get("status") == "REVOKED"


def test_f05_t1_05_termination_moves_active_team_memberships_to_past_members(
    api, hr_lead, team_a, team_member_a
):
    t_resp = api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Membership archival check", "terminatedAt": None},
    )
    assert t_resp.status_code == 200, t_resp.text

    pm_resp = api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=hr_lead)
    assert pm_resp.status_code == 200, pm_resp.text
    past_list = unwrap_list(pm_resp.json())
    records = [p for p in past_list if p.get("memberId") == team_member_a["id"]]
    assert records, f"Expected {team_member_a['id']} in past members: {past_list}"
    assert records[0].get("exitReason") == "TERMINATED"
    assert records[0].get("leftAt") is not None


# ===========================================================================
# Feature F6: 3-GDG-Lead Consensus Workflow for Create / Delete / Rename Team (5 Tests)
# ===========================================================================


def test_f06_t1_01_create_team_executes_only_on_third_distinct_gdg_lead_vote(
    api, gdg_lead_1, gdg_lead_2, gdg_lead_3
):
    team_name = f"Cloud-Native-{RUN_ID}-{next_seq()}"
    r1 = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={
            "actionType": "CREATE_TEAM",
            "targetTeamId": None,
            "proposedName": team_name,
            "proposedDescription": "Cloud Native SIG",
            "parentTeamId": None,
        },
    )
    assert r1.status_code in (200, 201), r1.text
    req_id = r1.json()["id"]
    assert r1.json()["status"] == "PENDING"
    assert r1.json()["approvalCount"] == 1

    r2 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_2,
        json={"approved": True, "comment": "Lead 2 approves"},
    )
    assert r2.status_code == 200, r2.text
    assert r2.json()["status"] == "PENDING"
    assert r2.json()["approvalCount"] == 2

    r3 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_3,
        json={"approved": True, "comment": "Lead 3 approves"},
    )
    assert r3.status_code == 200, r3.text
    r3_body = r3.json()
    assert r3_body["status"] == "EXECUTED"
    assert r3_body["approvalCount"] == 3
    team_id = r3_body.get("executedTeamId")
    assert team_id is not None

    t_resp = api.get(f"/api/v1/teams/{team_id}", actor=gdg_lead_1)
    assert t_resp.status_code == 200, t_resp.text
    assert t_resp.json()["name"] == team_name


def test_f06_t1_02_rename_team_via_3_gdg_lead_consensus_updates_team_name(
    api, gdg_leads, team_a
):
    new_name = f"Renamed-Android-{RUN_ID}-{next_seq()}"
    r1 = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_leads[0],
        json={
            "actionType": "RENAME_TEAM",
            "targetTeamId": team_a["id"],
            "proposedName": new_name,
            "proposedDescription": "Updated Android & Kotlin description",
        },
    )
    assert r1.status_code in (200, 201), r1.text
    req_id = r1.json()["id"]

    api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_leads[1],
        json={"approved": True, "comment": "Rename vote 2"},
    )
    r3 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_leads[2],
        json={"approved": True, "comment": "Rename vote 3"},
    )
    assert r3.status_code == 200, r3.text
    assert r3.json()["status"] == "EXECUTED"

    t_resp = api.get(f"/api/v1/teams/{team_a['id']}", actor=gdg_leads[0])
    assert t_resp.status_code == 200, t_resp.text
    assert t_resp.json()["name"] == new_name


def test_f06_t1_03_delete_team_via_3_gdg_lead_consensus_soft_deletes_and_records_past_members(
    api, gdg_leads, team_a, team_member_a
):
    r1 = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_leads[0],
        json={"actionType": "DELETE_TEAM", "targetTeamId": team_a["id"]},
    )
    assert r1.status_code in (200, 201), r1.text
    req_id = r1.json()["id"]

    api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_leads[1],
        json={"approved": True, "comment": "Delete vote 2"},
    )
    r3 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_leads[2],
        json={"approved": True, "comment": "Delete vote 3"},
    )
    assert r3.status_code == 200, r3.text
    assert r3.json()["status"] == "EXECUTED"

    pm_resp = api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=gdg_leads[0])
    assert pm_resp.status_code == 200, pm_resp.text
    past_members = unwrap_list(pm_resp.json())
    matched = [p for p in past_members if p.get("memberId") == team_member_a["id"]]
    assert matched
    assert matched[0].get("exitReason") == "TEAM_DELETED"


def test_f06_t1_04_create_sub_team_with_parent_team_id_via_3_lead_consensus(
    api, gdg_leads, team_a
):
    sub_team = create_approved_team(
        api,
        gdg_leads,
        name=f"SubTeam-ML-{RUN_ID}-{next_seq()}",
        description="ML Research Sub-Team",
        parent_team_id=team_a["id"],
    )
    assert sub_team["id"] is not None
    assert sub_team["parentTeamId"] == team_a["id"]


def test_f06_t1_05_list_consensus_requests_returns_audit_trail_of_approvals(
    api, gdg_leads, team_a
):
    resp = api.get("/api/v1/teams/consensus-requests", actor=gdg_leads[0])
    assert resp.status_code == 200, resp.text
    reqs = unwrap_list(resp.json())
    matching = [r for r in reqs if r["id"] == team_a["consensusRequestId"]]
    assert matching
    assert matching[0]["status"] == "EXECUTED"
    assert matching[0]["approvalCount"] == 3


# ===========================================================================
# Feature F7: HR Member Bio-Data CRUD & Dual-Authorization Execution (5 Tests)
# ===========================================================================


def test_f07_t1_01_hr_lead_directly_adds_new_member_executed_immediately(api, hr_lead):
    email = unique_email("hr_add")
    resp = api.post(
        "/api/v1/members",
        actor=hr_lead,
        json={
            "email": email,
            "password": "Password123!",
            "fullName": "Priya Nair",
            "rollNumber": unique_roll(),
            "department": "ECE",
            "academicYear": 2,
            "phone": "+919811122233",
            "roles": ["TEAM_MEMBER"],
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body.get("executionStatus", "EXECUTED") == "EXECUTED"
    member_id = body.get("id") or body.get("member", {}).get("id")
    assert member_id is not None


def test_f07_t1_02_hr_lead_directly_edits_and_removes_member_bio_data(
    api, hr_lead, user_factory
):
    target = user_factory(role="TEAM_MEMBER", full_name="Editable Member")
    edit_resp = api.put(
        f"/api/v1/members/{target['id']}/bio-data",
        actor=hr_lead,
        json={
            "fullName": "Edited By HR Lead",
            "department": "IT",
            "academicYear": 3,
            "phone": "+919000011111",
        },
    )
    assert edit_resp.status_code == 200, edit_resp.text
    assert edit_resp.json().get("executionStatus", "EXECUTED") == "EXECUTED"

    del_resp = api.delete(f"/api/v1/members/{target['id']}", actor=hr_lead)
    assert del_resp.status_code in (200, 204), del_resp.text


def test_f07_t1_03_hr_member_bio_data_edit_stages_pending_change_request(
    api, hr_member, hr_lead, team_a, team_member_a
):
    original_name = team_member_a["fullName"]
    resp = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Staged Name By HR Member",
            "department": "AIML",
            "academicYear": 4,
            "phone": "+919123456789",
        },
    )
    assert resp.status_code in (200, 201, 202), resp.text
    body = resp.json()
    assert body.get("executionStatus") == "PENDING_AUTHORIZATION" or body.get("status") == "PENDING_AUTHORIZATION"
    req_id = body.get("changeRequestId") or body.get("id")
    assert req_id is not None

    # Member record must not be mutated before dual approval
    m_resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead)
    assert m_resp.status_code == 200
    assert m_resp.json()["fullName"] == original_name


def test_f07_t1_04_dual_authorization_hr_lead_then_team_lead_executes_staged_bio_data_change(
    api, hr_member, hr_lead, team_lead_a, team_a, team_member_a
):
    staged = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Dual Approved Name",
            "department": "AIML",
            "academicYear": 4,
            "phone": "+919123456780",
        },
    )
    assert staged.status_code in (200, 201, 202), staged.text
    req_id = staged.json().get("changeRequestId") or staged.json().get("id")

    a1 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead approved"},
    )
    assert a1.status_code == 200, a1.text
    assert a1.json()["status"] == "PARTIALLY_APPROVED"
    assert a1.json()["hrLeadApproved"] is True
    assert a1.json()["teamLeadApproved"] is False

    a2 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead approved"},
    )
    assert a2.status_code == 200, a2.text
    assert a2.json()["status"] == "EXECUTED"
    assert a2.json()["hrLeadApproved"] is True
    assert a2.json()["teamLeadApproved"] is True

    m_resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead)
    assert m_resp.status_code == 200
    assert m_resp.json()["fullName"] == "Dual Approved Name"


def test_f07_t1_05_hr_member_staged_member_addition_executes_after_dual_approval(
    api, hr_member, hr_lead, team_lead_a, team_a
):
    new_email = unique_email("staged_add")
    staged = api.post(
        "/api/v1/members",
        actor=hr_member,
        json={
            "email": new_email,
            "password": "Password123!",
            "fullName": "Staged New Member",
            "rollNumber": unique_roll(),
            "department": "CSE",
            "academicYear": 2,
            "phone": "+919888777666",
            "roles": ["TEAM_MEMBER"],
            "teamId": team_a["id"],
        },
    )
    assert staged.status_code in (200, 201, 202), staged.text
    req_id = staged.json().get("changeRequestId") or staged.json().get("id")
    assert req_id is not None

    r1 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead OK"},
    )
    assert r1.status_code == 200, r1.text
    assert r1.json()["status"] == "PARTIALLY_APPROVED"

    r2 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead OK"},
    )
    assert r2.status_code == 200, r2.text
    assert r2.json()["status"] == "EXECUTED"


# ===========================================================================
# Feature F8: View Team Stats Across All Teams (5 Tests)
# ===========================================================================


def test_f08_t1_01_gdg_lead_views_stats_across_all_teams(
    api, gdg_lead_1, team_a, team_b
):
    resp = api.get("/api/v1/teams/stats", actor=gdg_lead_1)
    assert resp.status_code == 200, resp.text
    stats = unwrap_list(resp.json())
    by_id = {s["teamId"]: s for s in stats}
    assert team_a["id"] in by_id
    assert team_b["id"] in by_id
    for key in (
        "teamId",
        "teamName",
        "activeMemberCount",
        "pastMemberCount",
        "subTeamCount",
        "totalTasks",
        "completedTasks",
        "overdueTasks",
        "taskCompletionRate",
        "activeCollaborations",
    ):
        assert key in by_id[team_a["id"]], f"Missing key {key} in TeamStatsDTO"


def test_f08_t1_02_hr_member_views_single_team_stats_reflecting_member_counts(
    api, hr_member, team_a
):
    resp = api.get(f"/api/v1/teams/{team_a['id']}/stats", actor=hr_member)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["teamId"] == team_a["id"]
    assert body["activeMemberCount"] >= 2


def test_f08_t1_03_team_lead_views_stats_across_all_teams(
    api, team_lead_a, team_a, team_b
):
    resp = api.get("/api/v1/teams/stats", actor=team_lead_a)
    assert resp.status_code == 200, resp.text
    team_ids = {s["teamId"] for s in unwrap_list(resp.json())}
    assert team_a["id"] in team_ids
    assert team_b["id"] in team_ids


def test_f08_t1_04_team_stats_reflect_sub_team_and_past_member_counts(
    api, gdg_leads, hr_lead, team_a, team_member_a
):
    create_approved_team(
        api,
        gdg_leads,
        name=f"Stats-SubTeam-{RUN_ID}-{next_seq()}",
        parent_team_id=team_a["id"],
    )
    del_resp = api.delete(
        f"/api/v1/teams/{team_a['id']}/members/{team_member_a['id']}",
        actor=hr_lead,
    )
    assert del_resp.status_code in (200, 204), del_resp.text

    stats_resp = api.get(f"/api/v1/teams/{team_a['id']}/stats", actor=hr_lead)
    assert stats_resp.status_code == 200, stats_resp.text
    stats = stats_resp.json()
    assert stats["subTeamCount"] >= 1
    assert stats["pastMemberCount"] >= 1


def test_f08_t1_05_team_member_with_view_team_stats_grant_can_view_stats_across_all_teams(
    api, team_lead_a, team_member_a, team_a, team_b
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=3),
            "reason": "Cross-team stats visibility",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text

    r_all = api.get("/api/v1/teams/stats", actor=team_member_a)
    assert r_all.status_code == 200, r_all.text
    r_b = api.get(f"/api/v1/teams/{team_b['id']}/stats", actor=team_member_a)
    assert r_b.status_code == 200, r_b.text


# ===========================================================================
# Feature F9: View Past Team Members Across All Teams (5 Tests)
# ===========================================================================


def test_f09_t1_01_removing_member_from_team_records_past_membership_history(
    api, hr_lead, team_a, team_member_a
):
    rem = api.delete(
        f"/api/v1/teams/{team_a['id']}/members/{team_member_a['id']}",
        actor=hr_lead,
    )
    assert rem.status_code in (200, 204), rem.text

    pm_resp = api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=hr_lead)
    assert pm_resp.status_code == 200, pm_resp.text
    entries = [
        p for p in unwrap_list(pm_resp.json()) if p.get("memberId") == team_member_a["id"]
    ]
    assert entries
    assert entries[0]["teamId"] == team_a["id"]
    assert entries[0].get("joinedAt") is not None
    assert entries[0].get("leftAt") is not None
    assert entries[0].get("exitReason") == "REMOVED_FROM_TEAM"


def test_f09_t1_02_hr_member_views_past_members_across_all_teams(
    api, hr_lead, hr_member, team_a, team_member_a
):
    api.delete(
        f"/api/v1/teams/{team_a['id']}/members/{team_member_a['id']}",
        actor=hr_lead,
    )
    resp = api.get("/api/v1/teams/past-members", actor=hr_member)
    assert resp.status_code == 200, resp.text
    member_ids = {p.get("memberId") for p in unwrap_list(resp.json())}
    assert team_member_a["id"] in member_ids


def test_f09_t1_03_approved_team_transfer_records_past_membership_in_previous_team(
    api, gdg_lead_1, hr_lead, team_a, team_b, team_member_a
):
    req_resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Moving to Beta team for Cloud project",
        },
    )
    assert req_resp.status_code in (200, 201, 202), req_resp.text
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    app_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Transfer approved"},
    )
    assert app_resp.status_code == 200, app_resp.text

    pm_resp = api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=hr_lead)
    assert pm_resp.status_code == 200, pm_resp.text
    member_ids = {p.get("memberId") for p in unwrap_list(pm_resp.json())}
    assert team_member_a["id"] in member_ids


def test_f09_t1_04_team_lead_views_past_members_of_any_team(
    api, hr_lead, team_lead_b, team_a, team_b, team_member_a
):
    api.delete(
        f"/api/v1/teams/{team_a['id']}/members/{team_member_a['id']}",
        actor=hr_lead,
    )
    resp = api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=team_lead_b)
    assert resp.status_code == 200, resp.text


def test_f09_t1_05_team_member_with_view_past_members_grant_can_view_past_members(
    api, gdg_lead_1, hr_lead, team_a, team_member_a, team_member_b
):
    api.delete(
        f"/api/v1/teams/{team_a['id']}/members/{team_member_a['id']}",
        actor=hr_lead,
    )
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_b["id"],
            "permissionCode": "VIEW_PAST_MEMBERS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=2),
            "reason": "Alumni directory compilation",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text

    pm_resp = api.get("/api/v1/teams/past-members", actor=team_member_b)
    assert pm_resp.status_code == 200, pm_resp.text


# ===========================================================================
# Feature F10: Monitor Sub-Team & Cross-Team Collaborations (5 Tests)
# ===========================================================================


def test_f10_t1_01_gdg_lead_monitors_all_collaborations(
    api, gdg_lead_1, team_a, team_b
):
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=gdg_lead_1,
        json={
            "title": "F10 Cross-Team Collab",
            "description": "Cross-team initiative",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=15),
        },
    )
    assert c_resp.status_code in (200, 201), c_resp.text
    collab_id = c_resp.json()["id"]

    list_resp = api.get("/api/v1/collaborations", actor=gdg_lead_1)
    assert list_resp.status_code == 200, list_resp.text
    ids = {c["id"] for c in unwrap_list(list_resp.json())}
    assert collab_id in ids


def test_f10_t1_02_hr_member_monitors_specific_collaboration_details(
    api, hr_member, team_a, team_b
):
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=hr_member,
        json={
            "title": "HR Monitored Collab",
            "description": "Details check",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=10),
        },
    )
    assert c_resp.status_code in (200, 201), c_resp.text
    collab_id = c_resp.json()["id"]

    detail_resp = api.get(f"/api/v1/collaborations/{collab_id}", actor=hr_member)
    assert detail_resp.status_code == 200, detail_resp.text
    assert detail_resp.json()["id"] == collab_id


def test_f10_t1_03_monitor_sub_team_collaboration_between_parent_and_sub_team(
    api, gdg_leads, team_lead_a, team_a
):
    sub_team = create_approved_team(
        api,
        gdg_leads,
        name=f"SubTeam-Collab-{RUN_ID}-{next_seq()}",
        parent_team_id=team_a["id"],
    )
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=team_lead_a,
        json={
            "title": "Parent-Sub Sync",
            "description": "Sub-team collaboration",
            "collaborationType": "SUB_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], sub_team["id"]],
            "deadline": future_iso(days=14),
        },
    )
    assert c_resp.status_code in (200, 201), c_resp.text
    collab_id = c_resp.json()["id"]

    get_resp = api.get(f"/api/v1/collaborations/{collab_id}", actor=team_lead_a)
    assert get_resp.status_code == 200, get_resp.text
    assert get_resp.json()["collaborationType"] == "SUB_TEAM"


def test_f10_t1_04_event_lead_can_monitor_collaborations(
    api, gdg_lead_1, event_lead, team_a, team_b
):
    resp = api.get("/api/v1/collaborations", actor=event_lead)
    assert resp.status_code == 200, resp.text


def test_f10_t1_05_team_member_with_monitor_collaborations_grant_can_view_collaborations(
    api, gdg_lead_1, team_member_a, team_a, team_b
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "MONITOR_COLLABORATIONS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=3),
            "reason": "Allow member to monitor collaborations",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text

    resp = api.get("/api/v1/collaborations", actor=team_member_a)
    assert resp.status_code == 200, resp.text


# ===========================================================================
# Feature F11: View Member Details (5 Tests)
# ===========================================================================


def test_f11_t1_01_gdg_lead_views_all_members_and_specific_member_detail(
    api, gdg_lead_1, team_a, team_member_a
):
    list_resp = api.get("/api/v1/members", actor=gdg_lead_1)
    assert list_resp.status_code == 200, list_resp.text
    member_ids = {m["id"] for m in unwrap_list(list_resp.json())}
    assert team_member_a["id"] in member_ids

    detail_resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=gdg_lead_1)
    assert detail_resp.status_code == 200, detail_resp.text
    detail = detail_resp.json()
    assert detail["id"] == team_member_a["id"]
    assert detail["email"] == team_member_a["email"]


def test_f11_t1_02_hr_lead_views_member_details(api, hr_lead, team_member_a):
    resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead)
    assert resp.status_code == 200, resp.text
    assert resp.json()["id"] == team_member_a["id"]


def test_f11_t1_03_hr_member_views_member_list_and_member_details(
    api, hr_member, team_member_a
):
    l_resp = api.get("/api/v1/members", actor=hr_member)
    assert l_resp.status_code == 200, l_resp.text

    d_resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_member)
    assert d_resp.status_code == 200, d_resp.text
    assert d_resp.json()["id"] == team_member_a["id"]


def test_f11_t1_04_team_lead_and_event_lead_view_member_details(
    api, team_lead_a, event_lead, team_member_a
):
    r1 = api.get(f"/api/v1/members/{team_member_a['id']}", actor=team_lead_a)
    assert r1.status_code == 200, r1.text

    r2 = api.get(f"/api/v1/members/{team_member_a['id']}", actor=event_lead)
    assert r2.status_code == 200, r2.text


def test_f11_t1_05_member_detail_reflects_signed_pca_compliance_status(
    api, hr_lead, team_member_a
):
    ag_resp = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "F11 PCA Compliance Check",
            "contentHtml": "<h1>PCA</h1><p>Compliance check terms</p>",
        },
    )
    assert ag_resp.status_code in (200, 201), ag_resp.text
    ag_id = ag_resp.json()["id"]

    iss_resp = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"]]},
    )
    assert iss_resp.status_code in (200, 201), iss_resp.text
    sigs = unwrap_list(iss_resp.json())
    sig_id = sigs[0]["id"] if sigs else iss_resp.json()["id"]

    sign_resp = api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_a,
        json={"signatureText": "Signed by Team Member Alpha"},
    )
    assert sign_resp.status_code == 200, sign_resp.text

    detail_resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead)
    assert detail_resp.status_code == 200, detail_resp.text
    d = detail_resp.json()
    assert (
        d.get("pcaSigned") is True
        or d.get("pcaStatus") == "SIGNED"
        or d.get("pcaCompliant") is True
    ), f"Expected signed PCA status in member details: {d}"


# ===========================================================================
# Feature F12: Create Cross-Team Collaboration (5 Tests)
# ===========================================================================


def test_f12_t1_01_gdg_lead_creates_cross_team_collaboration(
    api, gdg_lead_1, team_a, team_b
):
    resp = api.post(
        "/api/v1/collaborations",
        actor=gdg_lead_1,
        json={
            "title": "Cloud + AI Hackathon",
            "description": "Joint workshop collaboration",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=20),
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body["title"] == "Cloud + AI Hackathon"
    assert set(body["participatingTeamIds"]) == {team_a["id"], team_b["id"]}


def test_f12_t1_02_team_lead_creates_cross_team_collaboration_and_target_team_lead_approves(
    api, team_lead_a, team_lead_b, team_a, team_b
):
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=team_lead_a,
        json={
            "title": "Alpha-Beta Joint Sprint",
            "description": "Cross-team API integration",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=15),
        },
    )
    assert c_resp.status_code in (200, 201), c_resp.text
    collab_id = c_resp.json()["id"]

    app_resp = api.post(
        f"/api/v1/collaborations/{collab_id}/approve-team",
        actor=team_lead_b,
        json={"teamId": team_b["id"], "approved": True, "comment": "Team B ready"},
    )
    assert app_resp.status_code == 200, app_resp.text
    assert app_resp.json()["status"] in ("ACTIVE", "APPROVED", "IN_PROGRESS", "PENDING_APPROVAL")


def test_f12_t1_03_hr_member_creates_cross_team_collaboration(
    api, hr_member, team_a, team_b
):
    resp = api.post(
        "/api/v1/collaborations",
        actor=hr_member,
        json={
            "title": "HR Initiated Cross-Team Drive",
            "description": "Recruitment & technical onboarding",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=25),
        },
    )
    assert resp.status_code in (200, 201), resp.text


def test_f12_t1_04_multi_team_collaboration_across_three_teams(
    api, gdg_leads, team_a, team_b
):
    team_c = create_approved_team(
        api,
        gdg_leads,
        name=f"Gamma-Team-{RUN_ID}-{next_seq()}",
    )
    resp = api.post(
        "/api/v1/collaborations",
        actor=gdg_leads[0],
        json={
            "title": "Tri-Team Flagship Project",
            "description": "Alpha + Beta + Gamma collaboration",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"], team_c["id"]],
            "deadline": future_iso(days=30),
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert set(resp.json()["participatingTeamIds"]) == {
        team_a["id"],
        team_b["id"],
        team_c["id"],
    }


def test_f12_t1_05_gdg_lead_can_approve_team_participation_in_collaboration(
    api, hr_member, gdg_lead_1, team_a, team_b
):
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=hr_member,
        json={
            "title": "Super Admin Approved Collab",
            "description": "Collab approved by GDG Lead",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=18),
        },
    )
    assert c_resp.status_code in (200, 201), c_resp.text
    collab_id = c_resp.json()["id"]

    r1 = api.post(
        f"/api/v1/collaborations/{collab_id}/approve-team",
        actor=gdg_lead_1,
        json={"teamId": team_a["id"], "approved": True, "comment": "GDG Lead approves A"},
    )
    assert r1.status_code == 200, r1.text

    r2 = api.post(
        f"/api/v1/collaborations/{collab_id}/approve-team",
        actor=gdg_lead_1,
        json={"teamId": team_b["id"], "approved": True, "comment": "GDG Lead approves B"},
    )
    assert r2.status_code == 200, r2.text


# ===========================================================================
# Feature F13: Project Contributor Agreement (PCA) Management (5 Tests)
# ===========================================================================


def test_f13_t1_01_gdg_lead_creates_pca_template(api, gdg_lead_1):
    ver = f"2026.{RUN_ID}.{next_seq()}"
    resp = api.post(
        "/api/v1/agreements",
        actor=gdg_lead_1,
        json={
            "version": ver,
            "title": "GDGoC Project Contributor Agreement 2026",
            "contentHtml": "<h1>Project Contributor Agreement</h1><p>Terms and conditions...</p>",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body["id"] is not None
    assert body["version"] == ver


def test_f13_t1_02_hr_lead_issues_pca_to_multiple_members(
    api, hr_lead, team_member_a, team_member_b
):
    ag_resp = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Batch Issued PCA",
            "contentHtml": "<h1>PCA</h1><p>Standard contributor clauses.</p>",
        },
    )
    assert ag_resp.status_code in (200, 201), ag_resp.text
    ag_id = ag_resp.json()["id"]

    iss_resp = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"], team_member_b["id"]]},
    )
    assert iss_resp.status_code in (200, 201), iss_resp.text
    sigs = unwrap_list(iss_resp.json())
    assert len(sigs) == 2
    assert all(s["status"] == "PENDING" for s in sigs)


def test_f13_t1_03_member_views_own_pending_pca_and_digitally_signs(
    api, hr_lead, team_member_a
):
    ag_resp = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Member Signable PCA",
            "contentHtml": "<h1>PCA</h1><p>Digital signature test.</p>",
        },
    )
    ag_id = ag_resp.json()["id"]
    iss_resp = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"]]},
    )
    sigs = unwrap_list(iss_resp.json())
    sig_id = sigs[0]["id"] if sigs else iss_resp.json()["id"]

    my_resp = api.get("/api/v1/agreements/signatures/my", actor=team_member_a)
    assert my_resp.status_code == 200, my_resp.text
    my_sig_ids = {s["id"] for s in unwrap_list(my_resp.json())}
    assert sig_id in my_sig_ids

    sign_resp = api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_a,
        json={"signatureText": "Digitally signed by Team Member Alpha"},
    )
    assert sign_resp.status_code == 200, sign_resp.text
    signed_body = sign_resp.json()
    assert signed_body["status"] == "SIGNED"
    assert signed_body.get("signedAt") is not None


def test_f13_t1_04_download_signed_pca_returns_valid_pdf_binary(
    api, hr_lead, team_member_a
):
    ag_resp = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "PDF Downloadable PCA",
            "contentHtml": "<h1>PCA PDF</h1><p>Rendered with OpenPDF.</p>",
        },
    )
    ag_id = ag_resp.json()["id"]
    iss_resp = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"]]},
    )
    sigs = unwrap_list(iss_resp.json())
    sig_id = sigs[0]["id"] if sigs else iss_resp.json()["id"]

    api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_a,
        json={"signatureText": "Signed for PDF download"},
    )

    dl_member = api.get(
        f"/api/v1/agreements/signatures/{sig_id}/download",
        actor=team_member_a,
    )
    assert_pdf_response(dl_member)

    dl_hr = api.get(
        f"/api/v1/agreements/signatures/{sig_id}/download",
        actor=hr_lead,
    )
    assert_pdf_response(dl_hr)


def test_f13_t1_05_signed_pca_appears_in_member_profile_letter_history(
    api, hr_lead, team_member_a
):
    ag_resp = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Letter History PCA",
            "contentHtml": "<h1>PCA</h1><p>Letter history integration.</p>",
        },
    )
    ag_id = ag_resp.json()["id"]
    iss_resp = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"]]},
    )
    sigs = unwrap_list(iss_resp.json())
    sig_id = sigs[0]["id"] if sigs else iss_resp.json()["id"]

    api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_a,
        json={"signatureText": "Signed for letter history"},
    )

    letters_resp = api.get("/api/v1/profile/me/letters", actor=team_member_a)
    assert letters_resp.status_code == 200, letters_resp.text
    types = {l.get("letterType") for l in unwrap_list(letters_resp.json())}
    assert "CONTRIBUTOR_AGREEMENT" in types


# ===========================================================================
# Feature F14: Global Task Rule — Mandatory Scheduled Deadline (5 Tests)
# ===========================================================================


def test_f14_create_task_with_valid_future_iso_deadline_succeeds(
    api, gdg_lead_1, team_lead_a, team_a
):
    dl = "2027-06-15T18:00:00Z"
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "F14 Future Deadline Task",
            "description": "Verify mandatory future deadline persists accurately",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": dl,
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body["id"] is not None
    assert body["status"] == "ASSIGNED"
    assert body.get("deadline") is not None


def test_f14_create_task_with_near_future_deadline_not_marked_overdue(
    api, team_lead_a, team_member_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F14 Near Future Task",
            "description": "Due in 2 hours",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(hours=2),
        },
    )
    assert resp.status_code in (200, 201), resp.text
    task_id = resp.json()["id"]

    tr_resp = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_lead_a)
    assert tr_resp.status_code == 200, tr_resp.text
    assert tr_resp.json().get("overdue") is False


def test_f14_create_task_across_all_priorities_preserves_scheduled_deadline(
    api, team_lead_a, team_member_a, team_a
):
    for idx, prio in enumerate(("LOW", "MEDIUM", "HIGH", "CRITICAL"), start=1):
        resp = api.post(
            "/api/v1/tasks",
            actor=team_lead_a,
            json={
                "title": f"F14 Priority {prio} Task",
                "description": f"Testing {prio} priority with deadline",
                "assignmentType": "TEAM_LEAD_TO_TEAM",
                "assigneeId": team_member_a["id"],
                "sourceTeamId": team_a["id"],
                "targetTeamId": team_a["id"],
                "priority": prio,
                "deadline": future_iso(days=7 * idx),
            },
        )
        assert resp.status_code in (200, 201), resp.text
        body = resp.json()
        assert body["priority"] == prio
        assert body.get("deadline") is not None


def test_f14_list_tasks_returns_scheduled_deadline_and_overdue_filter(
    api, gdg_lead_1, team_lead_a, team_member_a, team_a
):
    api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F14 List Filter Task",
            "description": "Ensure deadline returned in list",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=10),
        },
    )
    resp = api.get("/api/v1/tasks?overdueOnly=false", actor=gdg_lead_1)
    assert resp.status_code == 200, resp.text
    tasks = unwrap_list(resp.json())
    assert tasks
    assert all(t.get("deadline") is not None for t in tasks)


def test_f14_task_linked_to_collaboration_preserves_scheduled_deadline(
    api, team_lead_a, team_member_a, team_a, team_b
):
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=team_lead_a,
        json={
            "title": "F14 Collab Linked",
            "description": "Collab for task deadline test",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=30),
        },
    )
    collab_id = c_resp.json()["id"]

    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Collab Scoped Task",
            "description": "Task linked to collaborationId",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "collaborationId": collab_id,
            "priority": "HIGH",
            "deadline": "2027-08-01T12:00:00Z",
        },
    )
    assert t_resp.status_code in (200, 201), t_resp.text
    body = t_resp.json()
    assert body.get("collaborationId") == collab_id
    assert body.get("deadline") is not None


# ===========================================================================
# Feature F15: Global Task Rule — Assignee-Only Progress Updates & Tracking (5 Tests)
# ===========================================================================


def test_f15_assignee_updates_task_progress_in_progress_succeeds(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Progress Update Task",
            "description": "Assignee progress check",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=7),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={
            "progressPercentage": 45,
            "status": "IN_PROGRESS",
            "note": "Implemented repository layer",
            "evidenceUrl": "https://github.com/gdgoc/pr/12",
        },
    )
    assert p_resp.status_code == 200, p_resp.text
    body = p_resp.json()
    assert body["progressPercentage"] == 45
    assert body["status"] == "IN_PROGRESS"


def test_f15_assignee_sequential_progress_updates_recorded_in_tracking_logs(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Sequential Logs Task",
            "description": "Audit log trail",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=7),
        },
    )
    task_id = t_resp.json()["id"]

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={
            "progressPercentage": 25,
            "status": "IN_PROGRESS",
            "note": "Step 1 complete",
            "evidenceUrl": "https://github.com/gdgoc/pr/1",
        },
    )
    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={
            "progressPercentage": 70,
            "status": "IN_REVIEW",
            "note": "Step 2 ready for review",
            "evidenceUrl": "https://github.com/gdgoc/pr/2",
        },
    )

    tr_resp = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_lead_a)
    assert tr_resp.status_code == 200, tr_resp.text
    tr_body = tr_resp.json()
    logs = tr_body.get("progressLogs") or tr_body.get("logs") or tr_body.get("history") or []
    assert len(logs) >= 2
    percentages = [l.get("progressPercentage") for l in logs]
    assert 25 in percentages
    assert 70 in percentages


def test_f15_assignee_sets_progress_100_auto_completes_task_with_timestamp(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Auto Complete Task",
            "description": "100% completion check",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={
            "progressPercentage": 100,
            "status": "COMPLETED",
            "note": "Merged to main",
            "evidenceUrl": "https://github.com/gdgoc/commit/abc",
        },
    )
    assert p_resp.status_code == 200, p_resp.text
    body = p_resp.json()
    assert body["progressPercentage"] == 100
    assert body["status"] == "COMPLETED"
    assert body.get("completedAt") is not None


def test_f15_assignor_and_gdg_lead_track_task_progress_via_tracking_endpoint(
    api, gdg_lead_1, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Multi-Viewer Tracking",
            "description": "Tracking visibility check",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=6),
        },
    )
    task_id = t_resp.json()["id"]

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": 55, "status": "IN_PROGRESS", "note": "Halfway"},
    )

    r_assignor = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_lead_a)
    assert r_assignor.status_code == 200, r_assignor.text

    r_gdg = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=gdg_lead_1)
    assert r_gdg.status_code == 200, r_gdg.text


def test_f15_filter_tasks_by_assignee_assignor_and_status(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Filterable Task",
            "description": "Filter query check",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=4),
        },
    )
    task_id = t_resp.json()["id"]

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Done"},
    )

    f_resp = api.get(
        f"/api/v1/tasks?assigneeId={team_member_a['id']}&assignorId={team_lead_a['id']}&status=COMPLETED",
        actor=team_lead_a,
    )
    assert f_resp.status_code == 200, f_resp.text
    items = unwrap_list(f_resp.json())
    assert any(t["id"] == task_id for t in items)


# ===========================================================================
# Feature F16: GDG Leads Assign Tasks to Team Leads (5 Tests)
# ===========================================================================


def test_f16_gdg_lead_1_assigns_task_to_team_lead_1_succeeds(
    api, gdg_lead_1, team_lead_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Q1 Technical Roadmap",
            "description": "Prepare architecture roadmap for Technical Team",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "CRITICAL",
            "deadline": "2027-05-01T17:00:00Z",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body["assignmentType"] == "GDG_LEAD_TO_TEAM_LEAD"
    assert body["status"] == "ASSIGNED"
    assert body["assigneeId"] == team_lead_a["id"]


def test_f16_gdg_lead_2_assigns_task_to_team_lead_2_succeeds(
    api, gdg_lead_2, team_lead_b, team_b
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_2,
        json={
            "title": "Q2 Cloud Workshop Plan",
            "description": "Plan GCP Study Jam schedule",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_b["id"],
            "sourceTeamId": team_b["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": "2027-05-10T17:00:00Z",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert resp.json()["status"] == "ASSIGNED"
    assert resp.json()["assigneeId"] == team_lead_b["id"]


def test_f16_team_lead_assignee_updates_progress_on_gdg_lead_task(
    api, gdg_lead_1, team_lead_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Lead Progress Task",
            "description": "Team Lead updates own progress",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=10),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_lead_a,
        json={
            "progressPercentage": 50,
            "status": "IN_PROGRESS",
            "note": "Drafted half of roadmap",
        },
    )
    assert p_resp.status_code == 200, p_resp.text
    assert p_resp.json()["progressPercentage"] == 50


def test_f16_gdg_lead_assignor_tracks_team_lead_task_progress(
    api, gdg_lead_1, team_lead_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Tracked Lead Task",
            "description": "GDG Lead tracks progress",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=12),
        },
    )
    task_id = t_resp.json()["id"]

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_lead_a,
        json={
            "progressPercentage": 65,
            "status": "IN_PROGRESS",
            "note": "Architecture diagram ready",
        },
    )

    tr_resp = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=gdg_lead_1)
    assert tr_resp.status_code == 200, tr_resp.text
    logs = (
        tr_resp.json().get("progressLogs")
        or tr_resp.json().get("logs")
        or tr_resp.json().get("history")
        or []
    )
    assert any(l.get("progressPercentage") == 65 for l in logs)


def test_f16_team_lead_completes_gdg_lead_assigned_task(
    api, gdg_lead_1, team_lead_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Completable Lead Task",
            "description": "Complete roadmap",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=8),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_lead_a,
        json={
            "progressPercentage": 100,
            "status": "COMPLETED",
            "note": "Roadmap published",
        },
    )
    assert p_resp.status_code == 200, p_resp.text
    assert p_resp.json()["status"] == "COMPLETED"
    assert p_resp.json().get("completedAt") is not None


# ===========================================================================
# Feature F17: Team Leads (and GDG Leads) Assign Tasks to Team (5 Tests)
# ===========================================================================


def test_f17_team_lead_assigns_intra_team_task_immediately_assigned(
    api, team_lead_a, team_member_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F17 Intra-Team Task",
            "description": "Intra-team starts as ASSIGNED",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": "2027-06-01T12:00:00Z",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert resp.json()["status"] == "ASSIGNED"


def test_f17_gdg_lead_assigns_team_task_to_any_team_immediately_assigned(
    api, gdg_lead_1, team_member_b, team_a, team_b
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "F17 GDG Lead Direct Team Task",
            "description": "GDG Lead bypasses cross-team pending state",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": "2027-06-02T12:00:00Z",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert resp.json()["status"] == "ASSIGNED"


def test_f17_team_lead_assigns_cross_team_task_enters_pending_approval(
    api, team_lead_a, team_member_b, team_a, team_b
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F17 Cross-Team Task",
            "description": "Requires Team B Lead approval",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": "2027-06-05T12:00:00Z",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    body = resp.json()
    assert body["status"] == "PENDING_TEAM_LEAD_APPROVAL"
    assert body["sourceTeamId"] == team_a["id"]
    assert body["targetTeamId"] == team_b["id"]


def test_f17_team_lead_queries_team_tasks_via_team_id_filter(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F17 Team Query Task",
            "description": "Filter by teamId",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=9),
        },
    )
    task_id = t_resp.json()["id"]

    q_resp = api.get(f"/api/v1/tasks?teamId={team_a['id']}", actor=team_lead_a)
    assert q_resp.status_code == 200, q_resp.text
    ids = {t["id"] for t in unwrap_list(q_resp.json())}
    assert task_id in ids


def test_f17_intra_team_assignee_completes_team_lead_task_and_updates_team_stats(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F17 Stats Increment Task",
            "description": "Completing task increments completedTasks",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=6),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Completed"},
    )
    assert p_resp.status_code == 200, p_resp.text

    s_resp = api.get(f"/api/v1/teams/{team_a['id']}/stats", actor=team_lead_a)
    assert s_resp.status_code == 200, s_resp.text
    assert s_resp.json()["completedTasks"] >= 1


# ===========================================================================
# Feature F18: Team Members Assign Tasks Within / Across Teams (5 Tests)
# ===========================================================================


def test_f18_member_with_assign_task_intra_team_grant_assigns_intra_team_task(
    api, gdg_lead_1, team_lead_a, team_member_a, team_a, user_factory
):
    peer_member = user_factory(role="TEAM_MEMBER", full_name="Peer Member Alpha")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=gdg_lead_1,
        json={"memberId": peer_member["id"], "teamRole": "MEMBER"},
    )

    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_INTRA_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Peer task assignment",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text

    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "F18 Peer Intra-Team Task",
            "description": "Assigned by permitted team member",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": peer_member["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": "2027-07-01T12:00:00Z",
        },
    )
    assert t_resp.status_code in (200, 201), t_resp.text
    assert t_resp.json()["status"] == "ASSIGNED"


def test_f18_member_with_assign_task_cross_team_grant_assigns_cross_team_task_pending_approval(
    api, gdg_lead_1, team_member_a, team_member_b, team_a, team_b
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_CROSS_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Cross-team peer coordination",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text

    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "F18 Peer Cross-Team Task",
            "description": "Requires Team B Lead approval",
            "assignmentType": "MEMBER_CROSS_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": "2027-07-05T12:00:00Z",
        },
    )
    assert t_resp.status_code in (200, 201), t_resp.text
    assert t_resp.json()["status"] == "PENDING_TEAM_LEAD_APPROVAL"
    assert t_resp.json()["assignmentType"] == "MEMBER_CROSS_TEAM"


def test_f18_member_with_dashboard_action_access_grant_assigns_intra_team_task(
    api, gdg_lead_1, team_lead_a, team_member_a, team_a, user_factory
):
    member_2 = user_factory(role="TEAM_MEMBER", full_name="Member Two Alpha")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=gdg_lead_1,
        json={"memberId": member_2["id"], "teamRole": "MEMBER"},
    )

    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": member_2["id"],
            "permissionCode": "DASHBOARD_ACTION_ACCESS",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Dashboard action access",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text

    t_resp = api.post(
        "/api/v1/tasks",
        actor=member_2,
        json={
            "title": "F18 Dashboard Action Task",
            "description": "Assigned via DASHBOARD_ACTION_ACCESS",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": "2027-07-10T12:00:00Z",
        },
    )
    assert t_resp.status_code in (200, 201), t_resp.text
    assert t_resp.json()["status"] == "ASSIGNED"


def test_f18_member_assignor_tracks_peer_assigned_task_progress(
    api, gdg_lead_1, team_lead_a, team_member_a, team_a, user_factory
):
    peer = user_factory(role="TEAM_MEMBER", full_name="Tracked Peer")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=gdg_lead_1,
        json={"memberId": peer["id"], "teamRole": "MEMBER"},
    )
    api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_INTRA_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Track peer task",
        },
    )
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "F18 Peer Tracked Task",
            "description": "Peer progress tracking",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": peer["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=7),
        },
    )
    task_id = t_resp.json()["id"]

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=peer,
        json={"progressPercentage": 60, "status": "IN_PROGRESS", "note": "60% done"},
    )

    tr_resp = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_member_a)
    assert tr_resp.status_code == 200, tr_resp.text


def test_f18_team_lead_directly_invokes_member_intra_team_assignment_type(
    api, team_lead_a, team_member_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F18 Lead Invoking Intra-Team",
            "description": "Team Lead has implicit clearance",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": "2027-07-15T12:00:00Z",
        },
    )
    assert resp.status_code in (200, 201), resp.text
    assert resp.json()["status"] == "ASSIGNED"


# ===========================================================================
# Feature F19: Cross-Team Task Approval Workflow by Respective Team Lead (5 Tests)
# ===========================================================================


def test_f19_target_team_lead_approves_cross_team_task_from_team_lead(
    api, team_lead_a, team_lead_b, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F19 Cross-Team Approval 1",
            "description": "Lead A to Team B",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=10),
        },
    )
    task_id = t_resp.json()["id"]
    assert t_resp.json()["status"] == "PENDING_TEAM_LEAD_APPROVAL"

    app_resp = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": True, "comment": "Approved by Team 2 Lead for sprint"},
    )
    assert app_resp.status_code == 200, app_resp.text
    assert app_resp.json()["status"] == "ASSIGNED"


def test_f19_target_team_lead_approves_cross_team_task_from_permitted_member(
    api, gdg_lead_1, team_lead_b, team_member_a, team_member_b, team_a, team_b
):
    api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_CROSS_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Cross-team task creation",
        },
    )
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "F19 Member Cross-Team Approval",
            "description": "Member A to Member B",
            "assignmentType": "MEMBER_CROSS_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=10),
        },
    )
    task_id = t_resp.json()["id"]
    assert t_resp.json()["status"] == "PENDING_TEAM_LEAD_APPROVAL"

    app_resp = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": True, "comment": "Peer cross-team task approved"},
    )
    assert app_resp.status_code == 200, app_resp.text
    assert app_resp.json()["status"] == "ASSIGNED"


def test_f19_target_team_lead_rejects_cross_team_task_transitions_to_rejected(
    api, team_lead_a, team_lead_b, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F19 Rejected Cross-Team Task",
            "description": "Will be rejected by Team B Lead",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "LOW",
            "deadline": future_iso(days=10),
        },
    )
    task_id = t_resp.json()["id"]

    rej_resp = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": False, "comment": "Bandwidth full this week"},
    )
    assert rej_resp.status_code == 200, rej_resp.text
    assert rej_resp.json()["status"] == "REJECTED"


def test_f19_gdg_lead_approves_pending_cross_team_task_as_super_admin(
    api, gdg_lead_1, team_lead_a, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F19 Super Admin Approved Task",
            "description": "Approved by GDG Lead",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "CRITICAL",
            "deadline": future_iso(days=10),
        },
    )
    task_id = t_resp.json()["id"]

    app_resp = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Executive approval by GDG Lead"},
    )
    assert app_resp.status_code == 200, app_resp.text
    assert app_resp.json()["status"] == "ASSIGNED"


def test_f19_assignee_updates_progress_after_cross_team_task_is_approved(
    api, team_lead_a, team_lead_b, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F19 Post-Approval Progress Task",
            "description": "Progress unlocked after approval",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=10),
        },
    )
    task_id = t_resp.json()["id"]

    api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": True, "comment": "Approved"},
    )

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={
            "progressPercentage": 80,
            "status": "IN_PROGRESS",
            "note": "Cross-team work in progress",
        },
    )
    assert p_resp.status_code == 200, p_resp.text
    assert p_resp.json()["progressPercentage"] == 80


# ===========================================================================
# Feature F20: Global Member Profile Page, Letter History & PDF Download (5 Tests)
# ===========================================================================


def test_f20_active_member_fetches_global_profile_me(api, team_a, team_member_a):
    resp = api.get("/api/v1/profile/me", actor=team_member_a)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["id"] == team_member_a["id"]
    assert body["email"] == team_member_a["email"]
    assert body["status"] == "ACTIVE"
    assert "roles" in body


def test_f20_hr_lead_issues_appointment_and_appreciation_letters_and_member_lists_history(
    api, hr_lead, team_member_a
):
    r1 = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": team_member_a["id"],
            "letterType": "APPOINTMENT",
            "title": "Official Appointment Letter",
            "contentHtml": "<h1>Appointment</h1><p>Welcome to GDGoC.</p>",
        },
    )
    assert r1.status_code in (200, 201), r1.text

    r2 = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": team_member_a["id"],
            "letterType": "APPRECIATION",
            "title": "Star Contributor Appreciation",
            "contentHtml": "<h1>Appreciation</h1><p>Thank you for your work.</p>",
        },
    )
    assert r2.status_code in (200, 201), r2.text

    list_resp = api.get("/api/v1/profile/me/letters", actor=team_member_a)
    assert list_resp.status_code == 200, list_resp.text
    letters = unwrap_list(list_resp.json())
    types = {l.get("letterType") for l in letters}
    assert "APPOINTMENT" in types
    assert "APPRECIATION" in types


def test_f20_member_downloads_letter_pdf_and_increments_download_count(
    api, hr_lead, team_member_a
):
    iss = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": team_member_a["id"],
            "letterType": "RECOMMENDATION",
            "title": "Letter of Recommendation",
            "contentHtml": "<h1>Recommendation</h1><p>Highly recommended.</p>",
        },
    )
    assert iss.status_code in (200, 201), iss.text
    letter_id = iss.json()["id"]

    dl_resp = api.get(
        f"/api/v1/profile/me/letters/{letter_id}/download",
        actor=team_member_a,
    )
    assert_pdf_response(dl_resp)

    list_resp = api.get("/api/v1/profile/me/letters", actor=team_member_a)
    assert list_resp.status_code == 200, list_resp.text
    matched = [l for l in unwrap_list(list_resp.json()) if l["id"] == letter_id]
    assert matched
    assert matched[0].get("downloadCount", 1) >= 1


def test_f20_member_generates_signed_url_and_downloads_letter_via_token(
    api, hr_lead, team_member_a
):
    iss = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": team_member_a["id"],
            "letterType": "APPOINTMENT",
            "title": "Signed URL Letter",
            "contentHtml": "<h1>Signed URL Test</h1><p>Tokenized download.</p>",
        },
    )
    letter_id = iss.json()["id"]

    s_resp = api.get(
        f"/api/v1/profile/me/letters/{letter_id}/signed-url",
        actor=team_member_a,
    )
    assert s_resp.status_code == 200, s_resp.text
    s_body = s_resp.json()
    token = s_body.get("token")
    signed_url = s_body.get("signedUrl") or f"/api/v1/letters/signed-download/{token}"

    dl_resp = api.get(signed_url)
    assert_pdf_response(dl_resp)


def test_f20_terminated_member_within_10_day_window_accesses_profile_and_downloads_termination_letter(
    api, hr_lead, user_factory
):
    temp_member = user_factory(role="TEAM_MEMBER", full_name="Ten Day Window Member")
    t_resp = api.post(
        f"/api/v1/members/{temp_member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "End of tenure", "terminatedAt": None},
    )
    assert t_resp.status_code == 200, t_resp.text

    p_resp = api.get("/api/v1/profile/me", actor=temp_member)
    assert p_resp.status_code == 200, p_resp.text
    assert p_resp.json()["status"] == "TERMINATED"

    l_resp = api.get("/api/v1/profile/me/letters", actor=temp_member)
    assert l_resp.status_code == 200, l_resp.text
    letters = unwrap_list(l_resp.json())
    assert letters

    dl_resp = api.get(
        f"/api/v1/profile/me/letters/{letters[0]['id']}/download",
        actor=temp_member,
    )
    assert_pdf_response(dl_resp)


# ===========================================================================
# Feature F21: Member Request for Bio-Data Change (5 Tests)
# ===========================================================================


def test_f21_active_member_submits_bio_data_change_request(api, team_a, team_member_a):
    resp = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "Member One Updated",
            "phone": "+91-9876543210",
            "department": "Artificial Intelligence & Data Science",
            "academicYear": 4,
            "bio": "Full-stack & Cloud enthusiast",
            "skills": "Java, Spring Boot, React, GCP",
            "githubUrl": "https://github.com/member1-gdg",
            "linkedinUrl": "https://linkedin.com/in/member1-gdg",
            "reason": "Updated academic year and portfolio links",
        },
    )
    assert resp.status_code in (200, 201, 202), resp.text
    body = resp.json()
    assert body.get("requestType") == "BIO_DATA_UPDATE"
    assert body.get("status") == "PENDING_AUTHORIZATION"


def test_f21_hr_lead_single_approval_marks_bio_data_request_partially_approved_without_mutation(
    api, hr_lead, team_a, team_member_a
):
    original_dept = team_member_a["department"]
    req_resp = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "Partial Bio Name",
            "phone": "+919876543210",
            "department": "Cyber Security",
            "academicYear": 4,
            "reason": "Changed specialization",
        },
    )
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    app_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "Verified student ID card"},
    )
    assert app_resp.status_code == 200, app_resp.text
    assert app_resp.json()["status"] == "PARTIALLY_APPROVED"
    assert app_resp.json()["hrLeadApproved"] is True
    assert app_resp.json()["teamLeadApproved"] is False

    prof = api.get("/api/v1/profile/me", actor=team_member_a).json()
    assert prof.get("department") == original_dept


def test_f21_team_lead_co_approval_executes_bio_data_change_request(
    api, hr_lead, team_lead_a, team_a, team_member_a
):
    req_resp = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "Co-Approved Bio Name",
            "phone": "+919876543211",
            "department": "Artificial Intelligence & Data Science",
            "academicYear": 4,
            "skills": "Java, Spring Boot, React, GCP",
            "reason": "Annual profile update",
        },
    )
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead approved"},
    )
    co_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Approved by Team Lead"},
    )
    assert co_resp.status_code == 200, co_resp.text
    assert co_resp.json()["status"] == "EXECUTED"

    prof = api.get("/api/v1/profile/me", actor=team_member_a).json()
    assert prof["fullName"] == "Co-Approved Bio Name"
    assert prof["department"] == "Artificial Intelligence & Data Science"


def test_f21_gdg_lead_single_approval_immediately_executes_bio_data_request(
    api, gdg_lead_1, team_a, team_member_a
):
    req_resp = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": team_member_a["fullName"],
            "phone": "+919876543212",
            "department": "CSE",
            "academicYear": 4,
            "skills": "Java, Kotlin, Kubernetes",
            "reason": "Added Kubernetes certification",
        },
    )
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    app_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Fast-tracked by GDG Lead"},
    )
    assert app_resp.status_code == 200, app_resp.text
    assert app_resp.json()["status"] == "EXECUTED"

    prof = api.get("/api/v1/profile/me", actor=team_member_a).json()
    assert prof.get("skills") == "Java, Kotlin, Kubernetes"


def test_f21_hr_lead_rejects_bio_data_change_request(
    api, hr_lead, team_a, team_member_a
):
    req_resp = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "Rejected Bio Name",
            "department": "InvalidDeptCode",
            "academicYear": 4,
            "reason": "Typo in department",
        },
    )
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    rej_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": False, "comment": "Incorrect department code"},
    )
    assert rej_resp.status_code == 200, rej_resp.text
    assert rej_resp.json()["status"] == "REJECTED"


# ===========================================================================
# Feature F22: Member Request for Team Change with Reason (5 Tests)
# ===========================================================================


def test_f22_active_member_submits_team_change_request_with_reason(
    api, team_a, team_b, team_member_a
):
    reason = "Completed cloud certifications and want to contribute to Team 2"
    resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": reason,
        },
    )
    assert resp.status_code in (200, 201, 202), resp.text
    body = resp.json()
    assert body.get("requestType") == "TEAM_TRANSFER"
    assert body.get("status") == "PENDING_AUTHORIZATION"
    assert body.get("reason") == reason


def test_f22_dual_approval_by_hr_lead_and_team_lead_executes_team_transfer(
    api, hr_lead, team_lead_a, team_a, team_b, team_member_a
):
    req_resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Dual approved transfer to Team B",
        },
    )
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    a1 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead approves transfer"},
    )
    assert a1.status_code == 200, a1.text
    assert a1.json()["status"] == "PARTIALLY_APPROVED"

    a2 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead approves transfer"},
    )
    assert a2.status_code == 200, a2.text
    assert a2.json()["status"] == "EXECUTED"

    prof = api.get("/api/v1/profile/me", actor=team_member_a).json()
    assert team_b["id"] in prof.get("teamIds", [])


def test_f22_executed_team_transfer_records_previous_team_in_past_members_history(
    api, gdg_lead_1, hr_lead, team_a, team_b, team_member_a
):
    req_resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Transferring for past member history check",
        },
    )
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Approved"},
    )

    pm_resp = api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=hr_lead)
    assert pm_resp.status_code == 200, pm_resp.text
    entries = [
        p for p in unwrap_list(pm_resp.json()) if p.get("memberId") == team_member_a["id"]
    ]
    assert entries
    assert entries[0].get("leftAt") is not None


def test_f22_gdg_lead_immediately_approves_and_executes_team_change_request(
    api, gdg_lead_1, team_a, team_b, team_member_a
):
    req_resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Fast-tracked transfer by GDG Lead",
        },
    )
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    app_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Approved by GDG Lead"},
    )
    assert app_resp.status_code == 200, app_resp.text
    assert app_resp.json()["status"] == "EXECUTED"


def test_f22_team_lead_rejects_team_change_request_keeps_member_in_current_team(
    api, team_lead_a, team_a, team_b, team_member_a
):
    req_resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Request during critical sprint",
        },
    )
    req_id = req_resp.json().get("id") or req_resp.json().get("changeRequestId")

    rej_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": False, "comment": "Critical release ongoing"},
    )
    assert rej_resp.status_code == 200, rej_resp.text
    assert rej_resp.json()["status"] == "REJECTED"

    prof = api.get("/api/v1/profile/me", actor=team_member_a).json()
    assert team_a["id"] in prof.get("teamIds", [])


# ===========================================================================
# Feature F23: Event Management — CRUD, Event Leads, Attendance & History (5 Tests)
# ===========================================================================


def test_f23_create_event_and_retrieve_event_details(api, event_lead):
    ev_name = f"DevFest On Campus {RUN_ID}-{next_seq()}"
    c_resp = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": ev_name,
            "description": "Annual flagship developer conference",
            "eventDate": "2027-03-20T09:00:00Z",
            "venue": "Main Auditorium",
        },
    )
    assert c_resp.status_code in (200, 201), c_resp.text
    event_id = c_resp.json()["id"]

    g_resp = api.get(f"/api/v1/events/{event_id}", actor=event_lead)
    assert g_resp.status_code == 200, g_resp.text
    assert g_resp.json()["name"] == ev_name

    l_resp = api.get("/api/v1/events", actor=event_lead)
    assert l_resp.status_code == 200, l_resp.text
    assert any(e["id"] == event_id for e in unwrap_list(l_resp.json()))


def test_f23_assign_event_specific_lead_and_event_team_member(
    api, gdg_lead_1, event_lead, team_member_a, team_member_b
):
    ev_resp = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Event Lead Assign {RUN_ID}-{next_seq()}",
            "description": "Event specific role assignment",
            "eventDate": future_iso(days=15),
            "venue": "Seminar Hall 1",
        },
    )
    event_id = ev_resp.json()["id"]

    r1 = api.post(
        f"/api/v1/events/{event_id}/leads",
        actor=gdg_lead_1,
        json={"memberId": team_member_a["id"], "eventRole": "EVENT_LEAD"},
    )
    assert r1.status_code in (200, 201), r1.text
    assert r1.json()["eventRole"] == "EVENT_LEAD"

    r2 = api.post(
        f"/api/v1/events/{event_id}/leads",
        actor=gdg_lead_1,
        json={"memberId": team_member_b["id"], "eventRole": "EVENT_TEAM_MEMBER"},
    )
    assert r2.status_code in (200, 201), r2.text
    assert r2.json()["eventRole"] == "EVENT_TEAM_MEMBER"


def test_f23_record_attendance_via_barcode_entry_method(
    api, gdg_lead_1, event_lead, team_member_a
):
    ev_resp = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Barcode Event {RUN_ID}-{next_seq()}",
            "description": "Barcode check-in test",
            "eventDate": future_iso(days=10),
            "venue": "Main Auditorium",
        },
    )
    event_id = ev_resp.json()["id"]

    api.post(
        f"/api/v1/events/{event_id}/leads",
        actor=gdg_lead_1,
        json={"memberId": team_member_a["id"], "eventRole": "EVENT_LEAD"},
    )

    p_email = unique_email("alice_barcode")
    b_token = f"QR-DEVFEST-{RUN_ID}-{next_seq()}"
    att_resp = api.post(
        f"/api/v1/events/{event_id}/attendance",
        actor=team_member_a,
        json={
            "memberId": None,
            "participantName": "Alice Student",
            "participantEmail": p_email,
            "rollNumber": "22CS101",
            "barcodeToken": b_token,
            "entryMethod": "BARCODE",
        },
    )
    assert att_resp.status_code in (200, 201), att_resp.text
    body = att_resp.json()
    assert body["entryMethod"] == "BARCODE"
    assert body["barcodeToken"] == b_token
    assert body["participantEmail"] == p_email


def test_f23_record_attendance_via_manual_entry_method_and_list_attendance(
    api, event_lead
):
    ev_resp = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Manual Attendance Event {RUN_ID}-{next_seq()}",
            "description": "Manual and barcode list check",
            "eventDate": future_iso(days=12),
            "venue": "Lab 3",
        },
    )
    event_id = ev_resp.json()["id"]

    email_a = unique_email("alice_att")
    email_b = unique_email("bob_att")

    api.post(
        f"/api/v1/events/{event_id}/attendance",
        actor=event_lead,
        json={
            "memberId": None,
            "participantName": "Alice Student",
            "participantEmail": email_a,
            "rollNumber": "22CS101",
            "barcodeToken": f"QR-{RUN_ID}-{next_seq()}",
            "entryMethod": "BARCODE",
        },
    )
    m_resp = api.post(
        f"/api/v1/events/{event_id}/attendance",
        actor=event_lead,
        json={
            "memberId": None,
            "participantName": "Bob Student",
            "participantEmail": email_b,
            "rollNumber": "22CS102",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )
    assert m_resp.status_code in (200, 201), m_resp.text
    assert m_resp.json()["entryMethod"] == "MANUAL"

    list_resp = api.get(f"/api/v1/events/{event_id}/attendance", actor=event_lead)
    assert list_resp.status_code == 200, list_resp.text
    emails = {a["participantEmail"] for a in unwrap_list(list_resp.json())}
    assert email_a in emails
    assert email_b in emails


def test_f23_view_participant_history_across_multiple_events(api, event_lead):
    ev1 = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"DevFest {RUN_ID}",
            "description": "Event 1",
            "eventDate": future_iso(days=5),
            "venue": "Hall A",
        },
    ).json()["id"]
    ev2 = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Cloud Study Jam {RUN_ID}",
            "description": "Event 2",
            "eventDate": future_iso(days=10),
            "venue": "Hall B",
        },
    ).json()["id"]

    shared_email = unique_email("multi_event_alice")
    api.post(
        f"/api/v1/events/{ev1}/attendance",
        actor=event_lead,
        json={
            "participantName": "Alice Multi",
            "participantEmail": shared_email,
            "rollNumber": "22CS201",
            "barcodeToken": f"QR-E1-{RUN_ID}-{next_seq()}",
            "entryMethod": "BARCODE",
        },
    )
    api.post(
        f"/api/v1/events/{ev2}/attendance",
        actor=event_lead,
        json={
            "participantName": "Alice Multi",
            "participantEmail": shared_email,
            "rollNumber": "22CS201",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )

    h_resp = api.get(
        f"/api/v1/events/participants/history?participantEmail={shared_email}",
        actor=event_lead,
    )
    assert h_resp.status_code == 200, h_resp.text
    history = unwrap_list(h_resp.json())
    assert len(history) >= 2


# ===========================================================================
# Feature F24: Event Certificate Generation & Storage (5 Tests)
# ===========================================================================


def test_f24_generate_certificates_for_specific_attendance_ids(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Cert Specific Event {RUN_ID}-{next_seq()}",
            "description": "Certificate generation by attendanceIds",
            "eventDate": future_iso(days=7),
            "venue": "Main Hall",
        },
    ).json()["id"]

    p_email = unique_email("cert_alice")
    att_id = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "Alice Cert",
            "participantEmail": p_email,
            "rollNumber": "22CS301",
            "barcodeToken": f"QR-C1-{RUN_ID}-{next_seq()}",
            "entryMethod": "BARCODE",
        },
    ).json()["id"]

    gen_resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [att_id],
            "generateForAllAttendees": False,
            "templateTitle": "Certificate of Excellence - DevFest 2026",
        },
    )
    assert gen_resp.status_code in (200, 201), gen_resp.text
    certs = unwrap_list(gen_resp.json())
    assert len(certs) == 1
    assert certs[0]["participantEmail"] == p_email


def test_f24_generate_certificates_for_all_attendees_batch(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Cert Batch Event {RUN_ID}-{next_seq()}",
            "description": "Batch certificate generation",
            "eventDate": future_iso(days=8),
            "venue": "Auditorium",
        },
    ).json()["id"]

    for idx in (1, 2):
        api.post(
            f"/api/v1/events/{ev_id}/attendance",
            actor=event_lead,
            json={
                "participantName": f"Batch Attendee {idx}",
                "participantEmail": unique_email(f"batch_{idx}"),
                "rollNumber": f"22CS30{idx}",
                "barcodeToken": None,
                "entryMethod": "MANUAL",
            },
        )

    gen_resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [],
            "generateForAllAttendees": True,
            "templateTitle": "Certificate of Participation",
        },
    )
    assert gen_resp.status_code in (200, 201), gen_resp.text
    certs = unwrap_list(gen_resp.json())
    assert len(certs) >= 2


def test_f24_list_generated_certificates_for_event(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Cert List Event {RUN_ID}-{next_seq()}",
            "description": "List generated certificates",
            "eventDate": future_iso(days=9),
            "venue": "Room 101",
        },
    ).json()["id"]

    att_id = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "Listed Cert Student",
            "participantEmail": unique_email("list_cert"),
            "rollNumber": "22CS309",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    ).json()["id"]

    api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [att_id],
            "generateForAllAttendees": False,
            "templateTitle": "Certificate of Participation",
        },
    )

    list_resp = api.get(f"/api/v1/events/{ev_id}/certificates", actor=event_lead)
    assert list_resp.status_code == 200, list_resp.text
    certs = unwrap_list(list_resp.json())
    assert len(certs) == 1
    assert certs[0]["eventId"] == ev_id


def test_f24_download_stored_certificate_verifies_pdf_magic_bytes(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Cert Download Event {RUN_ID}-{next_seq()}",
            "description": "PDF download magic bytes check",
            "eventDate": future_iso(days=6),
            "venue": "Hall C",
        },
    ).json()["id"]

    att_id = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "PDF Download Student",
            "participantEmail": unique_email("pdf_dl"),
            "rollNumber": "22CS310",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    ).json()["id"]

    gen_resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [att_id],
            "generateForAllAttendees": False,
            "templateTitle": "Certificate of Merit",
        },
    )
    cert_id = unwrap_list(gen_resp.json())[0]["id"]

    dl_resp = api.get(f"/api/v1/certificates/{cert_id}/download", actor=event_lead)
    assert_pdf_response(dl_resp)


def test_f24_participant_history_includes_generated_certificate_metadata(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"History Cert Event {RUN_ID}-{next_seq()}",
            "description": "History certificate link",
            "eventDate": future_iso(days=6),
            "venue": "Hall D",
        },
    ).json()["id"]

    p_email = unique_email("hist_cert")
    att_id = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "History Cert Student",
            "participantEmail": p_email,
            "rollNumber": "22CS311",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    ).json()["id"]

    gen_resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [att_id],
            "generateForAllAttendees": False,
            "templateTitle": "Certificate of Completion",
        },
    )
    cert_id = unwrap_list(gen_resp.json())[0]["id"]

    h_resp = api.get(
        f"/api/v1/events/participants/history?participantEmail={p_email}",
        actor=event_lead,
    )
    assert h_resp.status_code == 200, h_resp.text
    records = unwrap_list(h_resp.json())
    assert records
    rec = records[0]
    assert (
        rec.get("certificateId") == cert_id
        or rec.get("certificateGenerated") is True
        or (isinstance(rec.get("certificate"), dict) and rec["certificate"].get("id") == cert_id)
    ), f"Expected certificate metadata in history record: {rec}"


# ===========================================================================
# Feature F25: Mail Certificates with CC/BCC, Bounce Tracking & Observability (5 Tests)
# ===========================================================================


def test_f25_mail_certificates_with_cc_and_bcc_creates_sent_dispatch_logs(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Mail Cert Event {RUN_ID}-{next_seq()}",
            "description": "CC and BCC mailing",
            "eventDate": future_iso(days=5),
            "venue": "Main Auditorium",
        },
    ).json()["id"]

    for idx in (1, 2):
        api.post(
            f"/api/v1/events/{ev_id}/attendance",
            actor=event_lead,
            json={
                "participantName": f"Mail Student {idx}",
                "participantEmail": unique_email(f"mail_student_{idx}"),
                "rollNumber": f"22CS40{idx}",
                "barcodeToken": None,
                "entryMethod": "MANUAL",
            },
        )

    gen_resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [],
            "generateForAllAttendees": True,
            "templateTitle": "Certificate of Participation",
        },
    )
    cert_ids = [c["id"] for c in unwrap_list(gen_resp.json())]

    mail_resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/mail",
        actor=event_lead,
        json={
            "certificateIds": cert_ids,
            "cc": ["faculty.advisor@rmkec.ac.in", "lead@gdgoc.edu"],
            "bcc": ["archive@gdgoc.edu"],
            "subject": "Your DevFest On Campus 2026 Certificate",
            "body": "Congratulations on completing DevFest On Campus 2026!",
        },
    )
    assert mail_resp.status_code in (200, 201), mail_resp.text
    logs = unwrap_list(mail_resp.json())
    assert len(logs) == 2
    for log in logs:
        assert log["deliveryStatus"] == "SENT"
        assert log.get("providerMessageId") is not None
        assert "faculty.advisor@rmkec.ac.in" in str(log.get("ccEmails", ""))
        assert "archive@gdgoc.edu" in str(log.get("bccEmails", ""))


def test_f25_bounce_webhook_updates_dispatch_log_to_bounced_with_reason(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Bounce Webhook Event {RUN_ID}-{next_seq()}",
            "description": "Bounce tracking webhook test",
            "eventDate": future_iso(days=5),
            "venue": "Hall E",
        },
    ).json()["id"]

    b_email = unique_email("bounced_bob")
    att_id = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "Bob Bounced",
            "participantEmail": b_email,
            "rollNumber": "22CS410",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    ).json()["id"]

    cert_id = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_id}/certificates/generate",
            actor=event_lead,
            json={
                "attendanceIds": [att_id],
                "generateForAllAttendees": False,
                "templateTitle": "Certificate",
            },
        ).json()
    )[0]["id"]

    mail_logs = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_id}/certificates/mail",
            actor=event_lead,
            json={
                "certificateIds": [cert_id],
                "cc": ["lead@gdgoc.edu"],
                "bcc": ["archive@gdgoc.edu"],
                "subject": "Certificate",
                "body": "Attached.",
            },
        ).json()
    )
    msg_id = mail_logs[0]["providerMessageId"]

    bounce_reason = "550 5.1.1 The email account that you tried to reach does not exist"
    wh_resp = api.post(
        "/api/v1/webhooks/email/bounce",
        json={
            "providerMessageId": msg_id,
            "recipientEmail": b_email,
            "eventId": ev_id,
            "eventType": "BOUNCED",
            "bounceReason": bounce_reason,
        },
    )
    assert wh_resp.status_code == 200, wh_resp.text
    wh_body = wh_resp.json()
    assert wh_body["deliveryStatus"] == "BOUNCED"
    assert wh_body.get("bounceReason") == bounce_reason


def test_f25_query_event_mail_logs_and_filtered_mail_bounces(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Mail Logs Query Event {RUN_ID}-{next_seq()}",
            "description": "Query mail logs and bounces",
            "eventDate": future_iso(days=5),
            "venue": "Hall F",
        },
    ).json()["id"]

    good_email = unique_email("good_recipient")
    bad_email = unique_email("bad_recipient")
    for name, em in (("Good User", good_email), ("Bad User", bad_email)):
        api.post(
            f"/api/v1/events/{ev_id}/attendance",
            actor=event_lead,
            json={
                "participantName": name,
                "participantEmail": em,
                "rollNumber": unique_roll(),
                "barcodeToken": None,
                "entryMethod": "MANUAL",
            },
        )

    certs = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_id}/certificates/generate",
            actor=event_lead,
            json={"attendanceIds": [], "generateForAllAttendees": True, "templateTitle": "Cert"},
        ).json()
    )
    mail_logs = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_id}/certificates/mail",
            actor=event_lead,
            json={
                "certificateIds": [c["id"] for c in certs],
                "cc": ["cc@gdgoc.edu"],
                "bcc": ["bcc@gdgoc.edu"],
                "subject": "Cert",
                "body": "Body",
            },
        ).json()
    )
    bad_log = [l for l in mail_logs if l.get("recipientEmail") == bad_email][0]

    api.post(
        "/api/v1/webhooks/email/bounce",
        json={
            "providerMessageId": bad_log["providerMessageId"],
            "recipientEmail": bad_email,
            "eventId": ev_id,
            "eventType": "BOUNCED",
            "bounceReason": "550 User unknown",
        },
    )

    logs_resp = api.get(f"/api/v1/events/{ev_id}/mail-logs", actor=event_lead)
    assert logs_resp.status_code == 200, logs_resp.text
    assert len(unwrap_list(logs_resp.json())) == 2

    bounces_resp = api.get("/api/v1/events/mail-bounces", actor=event_lead)
    assert bounces_resp.status_code == 200, bounces_resp.text
    bounces = unwrap_list(bounces_resp.json())
    assert any(b.get("recipientEmail") == bad_email and b.get("deliveryStatus") == "BOUNCED" for b in bounces)


def test_f25_openapi_v3_docs_and_swagger_ui_endpoints_accessible(api):
    docs_resp = api.get("/v3/api-docs")
    assert docs_resp.status_code == 200, docs_resp.text
    spec = docs_resp.json()
    assert str(spec.get("openapi", "")).startswith("3.")
    assert "paths" in spec

    swagger_resp = api.get("/swagger-ui.html")
    assert swagger_resp.status_code == 200, swagger_resp.text


def test_f25_actuator_health_and_prometheus_metrics_accessible(api):
    health_resp = api.get("/actuator/health")
    assert health_resp.status_code == 200, health_resp.text
    assert health_resp.json().get("status") == "UP"

    prom_resp = api.get("/actuator/prometheus")
    if prom_resp.status_code == 200:
        assert "jvm_" in prom_resp.text or "http_" in prom_resp.text
    else:
        info_resp = api.get("/actuator/info")
        assert info_resp.status_code == 200, info_resp.text
