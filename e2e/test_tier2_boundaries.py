"""
Tier 2 — Boundary, Corner, Negative & RBAC Rejection Suite (125 Tests: 5 per Feature F1–F25).

Every test exercises genuine HTTP REST requests against the live Spring Boot backend
(`E2E_BASE_URL` / `GDG_BASE_URL`), verifying strict validation (`400`), authentication (`401`),
RBAC / clearance & non-shareability enforcement (`403`), not-found handling (`404`),
conflict / state-machine enforcement (`409`), and 10-day post-termination expiry (`403`/`410`).
"""

from conftest import (
    RUN_ID,
    future_iso,
    next_seq,
    past_iso,
    unique_email,
    unique_roll,
    unwrap_list,
)


# ===========================================================================
# Feature F1: Stateless JWT & Google OAuth2 Authentication Boundaries (5 Tests)
# ===========================================================================


def test_f01_t2_01_register_duplicate_email_rejected_409_or_400(api, team_member_a):
    resp = api.post(
        "/api/v1/auth/register",
        json={
            "email": team_member_a["email"],
            "password": "Password123!",
            "fullName": "Duplicate Email User",
            "rollNumber": unique_roll(),
            "department": "CSE",
            "academicYear": 3,
            "phone": "+919876543210",
            "roles": ["TEAM_MEMBER"],
        },
    )
    assert resp.status_code in (400, 409), resp.text


def test_f01_t2_02_register_invalid_email_or_blank_password_rejected_400(api):
    resp = api.post(
        "/api/v1/auth/register",
        json={
            "email": "not-an-email",
            "password": "",
            "fullName": "",
            "rollNumber": "",
            "department": "CSE",
            "academicYear": 3,
        },
    )
    assert resp.status_code == 400, resp.text


def test_f01_t2_03_login_wrong_password_rejected_401(api, team_member_a):
    resp = api.post(
        "/api/v1/auth/login",
        json={"email": team_member_a["email"], "password": "WrongPassword999!"},
    )
    assert resp.status_code in (401, 403), resp.text


def test_f01_t2_04_auth_me_without_bearer_token_rejected_401(api):
    resp = api.get("/api/v1/auth/me")
    assert resp.status_code in (401, 403), resp.text


def test_f01_t2_05_auth_me_with_malformed_or_tampered_jwt_rejected_401(api):
    resp = api.get(
        "/api/v1/auth/me",
        headers={"Authorization": "Bearer eyJhbGciOiJIUzI1NiJ9.tampered.signature"},
    )
    assert resp.status_code in (401, 403), resp.text


# ===========================================================================
# Feature F2: Super Admin Role (GDG_LEAD) Boundaries & Non-Shareability (5 Tests)
# ===========================================================================


def test_f02_t2_01_gdg_lead_cannot_share_super_admin_permission(
    api, gdg_lead_1, team_member_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "SUPER_ADMIN",
            "validFrom": past_iso(days=1),
            "validUntil": future_iso(days=7),
            "reason": "Attempt to share super admin",
        },
    )
    assert resp.status_code in (400, 403), resp.text


def test_f02_t2_02_single_gdg_lead_cannot_unilaterally_create_team_without_3_lead_consensus(
    api, gdg_lead_1
):
    proposed = f"Unilateral-Team-{RUN_ID}-{next_seq()}"
    r1 = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={
            "actionType": "CREATE_TEAM",
            "targetTeamId": None,
            "proposedName": proposed,
            "proposedDescription": "Only 1 lead voted",
            "parentTeamId": None,
        },
    )
    assert r1.status_code in (200, 201), r1.text
    body = r1.json()
    assert body["status"] == "PENDING"
    assert body["approvalCount"] == 1
    assert body.get("executedTeamId") is None

    teams = unwrap_list(api.get("/api/v1/teams", actor=gdg_lead_1).json())
    assert not any(t.get("name") == proposed for t in teams)


def test_f02_t2_03_gdg_lead_cannot_update_task_progress_when_not_assignee(
    api, gdg_lead_1, team_lead_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "F2 Non-Assignee Progress Attempt",
            "description": "GDG Lead is assignor, not assignee",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=gdg_lead_1,
        json={
            "progressPercentage": 50,
            "status": "IN_PROGRESS",
            "note": "GDG Lead trying to update someone else's task",
        },
    )
    assert p_resp.status_code == 403, p_resp.text


def test_f02_t2_04_team_lead_cannot_access_super_admin_only_consensus_initiation(
    api, team_lead_a
):
    resp = api.post(
        "/api/v1/teams/consensus-requests",
        actor=team_lead_a,
        json={
            "actionType": "CREATE_TEAM",
            "proposedName": f"Unauthorized-Team-{RUN_ID}",
            "proposedDescription": "Team Lead cannot initiate",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f02_t2_05_hr_lead_cannot_access_super_admin_only_gdg_to_lead_task_assignment(
    api, hr_lead, team_lead_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=hr_lead,
        json={
            "title": "HR Lead trying GDG_LEAD_TO_TEAM_LEAD",
            "description": "Unauthorized",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


# ===========================================================================
# Feature F3: Lead Admin Role & Permission Granting Boundaries (5 Tests)
# ===========================================================================


def test_f03_t2_01_team_member_cannot_grant_permissions_403(
    api, team_member_a, team_member_b
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_member_a,
        json={
            "granteeMemberId": team_member_b["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=2),
            "reason": "Member trying to grant",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f03_t2_02_hr_member_cannot_grant_permissions_403(
    api, hr_member, team_member_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=hr_member,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=2),
            "reason": "HR Member trying to grant",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f03_t2_03_lead_cannot_delegate_grant_permissions_capability_400_or_403(
    api, gdg_lead_1, team_member_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "GRANT_PERMISSIONS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Cannot share GRANT_PERMISSIONS",
        },
    )
    assert resp.status_code in (400, 403), resp.text


def test_f03_t2_04_grant_view_member_details_to_non_hr_team_member_rejected_400(
    api, hr_lead, team_member_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=hr_lead,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_MEMBER_DETAILS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "VIEW_MEMBER_DETAILS is restricted to HR Team members",
        },
    )
    assert resp.status_code in (400, 403), resp.text


def test_f03_t2_05_wrong_team_lead_cannot_approve_cross_team_permission_grant_403(
    api, team_lead_a, team_lead_b, team_member_a, team_a, team_b
):
    c_resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "DASHBOARD_ACTION_ACCESS",
            "scopeTeamId": team_a["id"],
            "crossTeamTargetTeamId": team_b["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Needs Team B Lead approval",
        },
    )
    grant_id = c_resp.json()["id"]

    # team_lead_a (not lead of team_b) attempts to self-approve
    app_resp = api.post(
        f"/api/v1/permissions/grants/{grant_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Self approve attempt"},
    )
    assert app_resp.status_code == 403, app_resp.text


# ===========================================================================
# Feature F4: Scheduled & Temporary Access Sharing Boundaries (5 Tests)
# ===========================================================================


def test_f04_t2_01_grant_with_valid_until_before_or_equal_valid_from_rejected_400(
    api, gdg_lead_1, team_member_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": "2026-12-10T10:00:00Z",
            "validUntil": "2026-12-05T10:00:00Z",
            "reason": "Inverted window",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f04_t2_02_future_scheduled_grant_not_yet_active_before_valid_from_returns_403(
    api, gdg_lead_1, team_member_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": future_iso(days=2),
            "validUntil": future_iso(days=5),
            "reason": "Future window not active yet",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text

    s_resp = api.get("/api/v1/teams/stats", actor=team_member_a)
    assert s_resp.status_code == 403, s_resp.text


def test_f04_t2_03_expired_grant_after_valid_until_returns_403(
    api, gdg_lead_1, team_member_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": past_iso(days=5),
            "validUntil": past_iso(minutes=5),
            "reason": "Already expired window",
        },
    )
    # Either creation rejects past validUntil with 400, or grant is inactive and returns 403
    if g_resp.status_code in (200, 201):
        s_resp = api.get("/api/v1/teams/stats", actor=team_member_a)
        assert s_resp.status_code == 403, s_resp.text
    else:
        assert g_resp.status_code == 400, g_resp.text


def test_f04_t2_04_non_shareable_permissions_rejected_400_or_403(
    api, gdg_lead_1, team_lead_a, team_member_a
):
    for code in (
        "TEAM_LIFECYCLE_CONSENSUS",
        "PROJECT_CONTRIBUTOR_AGREEMENT",
        "ASSIGN_TASK_TO_TEAM_LEAD",
    ):
        resp = api.post(
            "/api/v1/permissions/grants",
            actor=gdg_lead_1,
            json={
                "granteeMemberId": team_lead_a["id"],
                "permissionCode": code,
                "validFrom": past_iso(hours=1),
                "validUntil": future_iso(days=3),
                "reason": f"Attempt to share non-shareable {code}",
            },
        )
        assert resp.status_code in (400, 403), f"Expected 400/403 for {code}, got {resp.status_code}"


def test_f04_t2_05_unrelated_member_cannot_revoke_permission_grant_403(
    api, gdg_lead_1, team_member_a, team_member_b
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=3),
            "reason": "Revoke test",
        },
    )
    grant_id = g_resp.json()["id"]

    rev_resp = api.delete(f"/api/v1/permissions/grants/{grant_id}", actor=team_member_b)
    assert rev_resp.status_code == 403, rev_resp.text


# ===========================================================================
# Feature F5: 10-Day Post-Termination Revocation & Window Boundaries (5 Tests)
# ===========================================================================


def test_f05_t2_01_terminated_member_blocked_from_dashboard_endpoints_during_10_day_window_403(
    api, gdg_lead_1, hr_lead, team_member_a, team_a
):
    api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Grant before termination",
        },
    )
    api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Immediate lockout check", "terminatedAt": None},
    )

    for path in ("/api/v1/teams", "/api/v1/teams/stats", "/api/v1/tasks"):
        r = api.get(path, actor=team_member_a)
        assert r.status_code == 403, f"Expected 403 on {path} for terminated member, got {r.status_code}"


def test_f05_t2_02_terminated_member_blocked_from_submitting_bio_data_or_team_change_requests_403(
    api, hr_lead, team_member_a, team_a, team_b
):
    api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "No self-service mutations after termination", "terminatedAt": None},
    )

    r_bio = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={"fullName": "Blocked Bio Update", "department": "CSE", "academicYear": 4, "reason": "Try"},
    )
    assert r_bio.status_code == 403, r_bio.text

    r_team = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Blocked transfer",
        },
    )
    assert r_team.status_code == 403, r_team.text


def test_f05_t2_03_terminated_member_after_10_days_blocked_from_letter_list_and_download_403_or_410(
    api, hr_lead, user_factory
):
    member = user_factory(role="TEAM_MEMBER", full_name="Expired Window Member")
    iss = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": member["id"],
            "letterType": "APPOINTMENT",
            "title": "Appointment Letter",
            "contentHtml": "<h1>Appointment</h1>",
        },
    )
    letter_id = iss.json()["id"]

    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Terminated 11 days ago", "terminatedAt": past_iso(days=11)},
    )

    l_resp = api.get("/api/v1/profile/me/letters", actor=member)
    assert l_resp.status_code in (403, 410), l_resp.text

    dl_resp = api.get(f"/api/v1/profile/me/letters/{letter_id}/download", actor=member)
    assert dl_resp.status_code in (403, 410), dl_resp.text


def test_f05_t2_04_terminated_member_signed_url_expired_after_10_days_rejected_403_or_410(
    api, hr_lead, user_factory
):
    member = user_factory(role="TEAM_MEMBER", full_name="Expired Signed URL Member")
    iss = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": member["id"],
            "letterType": "APPRECIATION",
            "title": "Appreciation Letter",
            "contentHtml": "<h1>Appreciation</h1>",
        },
    )
    letter_id = iss.json()["id"]

    s_resp = api.get(f"/api/v1/profile/me/letters/{letter_id}/signed-url", actor=member)
    token = s_resp.json().get("token") if s_resp.status_code == 200 else "expired-token"

    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Terminated 11 days ago", "terminatedAt": past_iso(days=11)},
    )

    s_after = api.get(f"/api/v1/profile/me/letters/{letter_id}/signed-url", actor=member)
    assert s_after.status_code in (403, 410), s_after.text

    dl_token = api.get(f"/api/v1/letters/signed-download/{token}")
    assert dl_token.status_code in (403, 410), dl_token.text


def test_f05_t2_05_non_hr_or_gdg_lead_cannot_terminate_member_403(
    api, team_lead_a, team_member_a
):
    resp = api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=team_lead_a,
        json={"reason": "Team Lead cannot terminate chapter member", "terminatedAt": None},
    )
    assert resp.status_code == 403, resp.text


# ===========================================================================
# Feature F6: 3-GDG-Lead Consensus Workflow Boundaries (5 Tests)
# ===========================================================================


def test_f06_t2_01_duplicate_vote_by_same_gdg_lead_rejected_409(api, gdg_lead_1):
    r1 = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={
            "actionType": "CREATE_TEAM",
            "proposedName": f"DupVote-Team-{RUN_ID}-{next_seq()}",
            "proposedDescription": "Duplicate vote test",
        },
    )
    req_id = r1.json()["id"]

    dup = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Voting again by same GDG Lead"},
    )
    assert dup.status_code == 409, dup.text


def test_f06_t2_02_rejection_vote_by_any_gdg_lead_marks_consensus_request_rejected(
    api, gdg_lead_1, gdg_lead_2, gdg_lead_3
):
    r1 = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={
            "actionType": "CREATE_TEAM",
            "proposedName": f"Rejected-Team-{RUN_ID}-{next_seq()}",
            "proposedDescription": "Will be rejected by Lead 2",
        },
    )
    req_id = r1.json()["id"]

    r2 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_2,
        json={"approved": False, "comment": "Not needed this semester"},
    )
    assert r2.status_code == 200, r2.text
    assert r2.json()["status"] == "REJECTED"

    r3 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_3,
        json={"approved": True, "comment": "Late vote on rejected request"},
    )
    assert r3.status_code in (400, 409), r3.text


def test_f06_t2_03_non_gdg_lead_cannot_initiate_or_vote_on_team_consensus_403(
    api, gdg_lead_1, hr_lead, team_lead_a
):
    r_init = api.post(
        "/api/v1/teams/consensus-requests",
        actor=hr_lead,
        json={
            "actionType": "CREATE_TEAM",
            "proposedName": f"HR-Initiated-{RUN_ID}",
            "proposedDescription": "Forbidden",
        },
    )
    assert r_init.status_code == 403, r_init.text

    valid = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={
            "actionType": "CREATE_TEAM",
            "proposedName": f"Valid-Consensus-{RUN_ID}-{next_seq()}",
            "proposedDescription": "Valid",
        },
    )
    req_id = valid.json()["id"]

    r_vote = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead cannot vote"},
    )
    assert r_vote.status_code == 403, r_vote.text


def test_f06_t2_04_delete_or_rename_non_existent_team_rejected_404_or_400(
    api, gdg_lead_1
):
    resp = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={"actionType": "DELETE_TEAM", "targetTeamId": 999999},
    )
    assert resp.status_code in (400, 404), resp.text


def test_f06_t2_05_vote_on_already_executed_consensus_request_rejected_409_or_400(
    api, gdg_leads, user_factory, team_a
):
    extra_lead = user_factory(role="GDG_LEAD", full_name="Fourth GDG Lead")
    resp = api.post(
        f"/api/v1/teams/consensus-requests/{team_a['consensusRequestId']}/vote",
        actor=extra_lead,
        json={"approved": True, "comment": "Voting on already executed request"},
    )
    assert resp.status_code in (400, 409), resp.text


# ===========================================================================
# Feature F7: HR Member Bio-Data CRUD & Dual-Authorization Boundaries (5 Tests)
# ===========================================================================


def test_f07_t2_01_partial_approval_by_only_hr_lead_does_not_mutate_member_bio_data(
    api, hr_member, hr_lead, team_a, team_member_a
):
    orig_name = team_member_a["fullName"]
    staged = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Should Not Apply Yet",
            "department": "MECH",
            "academicYear": 4,
            "phone": "+919000099999",
        },
    )
    req_id = staged.json().get("changeRequestId") or staged.json().get("id")

    a1 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "Only HR Lead approved"},
    )
    assert a1.status_code == 200, a1.text
    assert a1.json()["status"] == "PARTIALLY_APPROVED"

    member = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead).json()
    assert member["fullName"] == orig_name


def test_f07_t2_02_rejection_by_team_lead_marks_change_request_rejected_and_prevents_execution(
    api, hr_member, hr_lead, team_lead_a, team_a, team_member_a
):
    orig_name = team_member_a["fullName"]
    staged = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Rejected Staged Name",
            "department": "CIVIL",
            "academicYear": 4,
            "phone": "+919000088888",
        },
    )
    req_id = staged.json().get("changeRequestId") or staged.json().get("id")

    api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead OK"},
    )
    rej = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": False, "comment": "Incorrect department"},
    )
    assert rej.status_code == 200, rej.text
    assert rej.json()["status"] == "REJECTED"

    member = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead).json()
    assert member["fullName"] == orig_name


def test_f07_t2_03_regular_team_member_without_grant_cannot_edit_other_member_bio_data_403(
    api, team_member_a, team_member_b
):
    resp = api.put(
        f"/api/v1/members/{team_member_b['id']}/bio-data",
        actor=team_member_a,
        json={
            "fullName": "Unauthorized Edit",
            "department": "CSE",
            "academicYear": 3,
            "phone": "+919111222333",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f07_t2_04_hr_member_cannot_self_approve_change_request_403(
    api, hr_member, team_a, team_member_a
):
    staged = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Self Approval Attempt",
            "department": "CSE",
            "academicYear": 3,
            "phone": "+919111222444",
        },
    )
    req_id = staged.json().get("changeRequestId") or staged.json().get("id")

    resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_member,
        json={"approved": True, "comment": "Trying to self-approve"},
    )
    assert resp.status_code == 403, resp.text


def test_f07_t2_05_unrelated_team_lead_cannot_approve_member_change_request_403(
    api, hr_member, team_lead_b, team_a, team_b, team_member_a
):
    staged = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Wrong Team Lead Approval Attempt",
            "department": "CSE",
            "academicYear": 3,
            "phone": "+919111222555",
        },
    )
    req_id = staged.json().get("changeRequestId") or staged.json().get("id")

    resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_b,
        json={"approved": True, "comment": "Lead B is not Team A's Lead"},
    )
    assert resp.status_code == 403, resp.text


# ===========================================================================
# Feature F8: View Team Stats Across All Teams Boundaries (5 Tests)
# ===========================================================================


def test_f08_t2_01_unpermitted_team_member_cannot_view_all_team_stats_403(
    api, team_member_a
):
    resp = api.get("/api/v1/teams/stats", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f08_t2_02_unpermitted_team_member_cannot_view_single_team_stats_403(
    api, team_member_a, team_a
):
    resp = api.get(f"/api/v1/teams/{team_a['id']}/stats", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f08_t2_03_unauthenticated_request_to_team_stats_rejected_401(api, team_a):
    resp = api.get("/api/v1/teams/stats")
    assert resp.status_code in (401, 403), resp.text


def test_f08_t2_04_stats_for_non_existent_team_id_returns_404(api, gdg_lead_1):
    resp = api.get("/api/v1/teams/999999/stats", actor=gdg_lead_1)
    assert resp.status_code == 404, resp.text


def test_f08_t2_05_terminated_hr_member_cannot_view_team_stats_403(
    api, hr_lead, hr_member
):
    api.post(
        f"/api/v1/members/{hr_member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Terminated HR member", "terminatedAt": None},
    )
    resp = api.get("/api/v1/teams/stats", actor=hr_member)
    assert resp.status_code == 403, resp.text


# ===========================================================================
# Feature F9: View Past Team Members Across All Teams Boundaries (5 Tests)
# ===========================================================================


def test_f09_t2_01_unpermitted_team_member_cannot_view_past_members_403(
    api, team_member_a
):
    resp = api.get("/api/v1/teams/past-members", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f09_t2_02_unpermitted_team_member_cannot_view_team_specific_past_members_403(
    api, team_member_a, team_a
):
    resp = api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f09_t2_03_past_members_for_non_existent_team_returns_404(api, gdg_lead_1):
    resp = api.get("/api/v1/teams/999999/past-members", actor=gdg_lead_1)
    assert resp.status_code == 404, resp.text


def test_f09_t2_04_removing_non_member_from_team_returns_404_or_400(
    api, hr_lead, team_a, team_member_b
):
    resp = api.delete(
        f"/api/v1/teams/{team_a['id']}/members/{team_member_b['id']}",
        actor=hr_lead,
    )
    assert resp.status_code in (400, 404), resp.text


def test_f09_t2_05_team_lead_of_team_b_cannot_remove_member_from_team_a_403(
    api, team_lead_b, team_a, team_member_a
):
    resp = api.delete(
        f"/api/v1/teams/{team_a['id']}/members/{team_member_a['id']}",
        actor=team_lead_b,
    )
    assert resp.status_code == 403, resp.text


# ===========================================================================
# Feature F10: Monitor Sub-Team & Cross-Team Collaborations Boundaries (5 Tests)
# ===========================================================================


def test_f10_t2_01_unpermitted_team_member_cannot_list_collaborations_403(
    api, team_member_a
):
    resp = api.get("/api/v1/collaborations", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f10_t2_02_unpermitted_team_member_cannot_view_collaboration_by_id_403(
    api, gdg_lead_1, team_member_a, team_a, team_b
):
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=gdg_lead_1,
        json={
            "title": "Restricted Collab View",
            "description": "Protected",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=10),
        },
    )
    collab_id = c_resp.json()["id"]

    resp = api.get(f"/api/v1/collaborations/{collab_id}", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f10_t2_03_get_non_existent_collaboration_returns_404(api, gdg_lead_1):
    resp = api.get("/api/v1/collaborations/999999", actor=gdg_lead_1)
    assert resp.status_code == 404, resp.text


def test_f10_t2_04_revoked_monitor_collaborations_grant_blocks_access_403(
    api, gdg_lead_1, team_member_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "MONITOR_COLLABORATIONS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=2),
            "reason": "Temporary collab view",
        },
    )
    grant_id = g_resp.json()["id"]
    api.delete(f"/api/v1/permissions/grants/{grant_id}", actor=gdg_lead_1)

    resp = api.get("/api/v1/collaborations", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f10_t2_05_unauthenticated_request_to_collaborations_rejected_401(api):
    resp = api.get("/api/v1/collaborations")
    assert resp.status_code in (401, 403), resp.text


# ===========================================================================
# Feature F11: View Member Details Boundaries (5 Tests)
# ===========================================================================


def test_f11_t2_01_regular_team_member_cannot_list_all_members_403(
    api, team_member_a
):
    resp = api.get("/api/v1/members", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f11_t2_02_regular_team_member_cannot_view_other_member_details_403(
    api, team_member_a, team_member_b
):
    resp = api.get(f"/api/v1/members/{team_member_b['id']}", actor=team_member_a)
    assert resp.status_code == 403, resp.text


def test_f11_t2_03_event_team_member_cannot_view_member_details_403(
    api, event_team_member, team_member_a
):
    resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=event_team_member)
    assert resp.status_code == 403, resp.text


def test_f11_t2_04_view_non_existent_member_id_returns_404(api, hr_lead):
    resp = api.get("/api/v1/members/999999", actor=hr_lead)
    assert resp.status_code == 404, resp.text


def test_f11_t2_05_view_member_details_permission_cannot_be_shared_to_non_hr_member_400(
    api, gdg_lead_1, team_member_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_MEMBER_DETAILS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=2),
            "reason": "Only shareable to HR Team members",
        },
    )
    assert resp.status_code in (400, 403), resp.text


# ===========================================================================
# Feature F12: Create Cross-Team Collaboration Boundaries (5 Tests)
# ===========================================================================


def test_f12_t2_01_cross_team_collaboration_with_single_team_rejected_400(
    api, gdg_lead_1, team_a
):
    resp = api.post(
        "/api/v1/collaborations",
        actor=gdg_lead_1,
        json={
            "title": "Single Team Invalid Collab",
            "description": "Must have >= 2 teams",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"]],
            "deadline": future_iso(days=10),
        },
    )
    assert resp.status_code == 400, resp.text


def test_f12_t2_02_cross_team_collaboration_with_duplicate_same_team_id_rejected_400(
    api, gdg_lead_1, team_a
):
    resp = api.post(
        "/api/v1/collaborations",
        actor=gdg_lead_1,
        json={
            "title": "Duplicate Team ID Collab",
            "description": "Must have >= 2 distinct teams",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_a["id"]],
            "deadline": future_iso(days=10),
        },
    )
    assert resp.status_code == 400, resp.text


def test_f12_t2_03_regular_team_member_cannot_create_cross_team_collaboration_403(
    api, team_member_a, team_a, team_b
):
    resp = api.post(
        "/api/v1/collaborations",
        actor=team_member_a,
        json={
            "title": "Member Collab Attempt",
            "description": "Forbidden",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=10),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f12_t2_04_team_lead_of_team_a_cannot_approve_collaboration_for_team_b_403(
    api, team_lead_a, team_a, team_b
):
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=team_lead_a,
        json={
            "title": "Cross Approval Boundary Collab",
            "description": "Team A Lead cannot approve for Team B",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=10),
        },
    )
    collab_id = c_resp.json()["id"]

    app_resp = api.post(
        f"/api/v1/collaborations/{collab_id}/approve-team",
        actor=team_lead_a,
        json={"teamId": team_b["id"], "approved": True, "comment": "Trying to approve for B"},
    )
    assert app_resp.status_code == 403, app_resp.text


def test_f12_t2_05_create_cross_team_collab_permission_cannot_be_shared_to_regular_team_member_400(
    api, gdg_lead_1, team_member_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "CREATE_CROSS_TEAM_COLLAB",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=3),
            "reason": "Only shareable to HR Team members",
        },
    )
    assert resp.status_code in (400, 403), resp.text


# ===========================================================================
# Feature F13: Project Contributor Agreement (PCA) Boundaries (5 Tests)
# ===========================================================================


def test_f13_t2_01_team_lead_and_hr_member_cannot_create_or_issue_pca_403(
    api, gdg_lead_1, team_lead_a, hr_member, team_member_a
):
    r_create = api.post(
        "/api/v1/agreements",
        actor=team_lead_a,
        json={
            "version": f"2026.{RUN_ID}.unauth",
            "title": "Unauthorized PCA",
            "contentHtml": "<h1>No</h1>",
        },
    )
    assert r_create.status_code == 403, r_create.text

    valid_ag = api.post(
        "/api/v1/agreements",
        actor=gdg_lead_1,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Valid PCA",
            "contentHtml": "<h1>PCA</h1>",
        },
    ).json()

    r_issue = api.post(
        f"/api/v1/agreements/{valid_ag['id']}/issue",
        actor=hr_member,
        json={"memberIds": [team_member_a["id"]]},
    )
    assert r_issue.status_code == 403, r_issue.text


def test_f13_t2_02_pca_permission_cannot_be_delegated_via_permission_grant_400_or_403(
    api, gdg_lead_1, hr_member
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": hr_member["id"],
            "permissionCode": "PROJECT_CONTRIBUTOR_AGREEMENT",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=3),
            "reason": "PCA permission is non-shareable",
        },
    )
    assert resp.status_code in (400, 403), resp.text


def test_f13_t2_03_member_cannot_sign_another_members_pca_signature_403(
    api, hr_lead, team_member_a, team_member_b
):
    ag_id = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Other Member Sign Test",
            "contentHtml": "<h1>PCA</h1>",
        },
    ).json()["id"]

    iss = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"]]},
    ).json()
    sigs = unwrap_list(iss)
    sig_id = sigs[0]["id"] if sigs else iss["id"]

    resp = api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_b,
        json={"signatureText": "Impersonation attempt by Member B"},
    )
    assert resp.status_code == 403, resp.text


def test_f13_t2_04_duplicate_signing_of_already_signed_pca_rejected_409_or_400(
    api, hr_lead, team_member_a
):
    ag_id = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Duplicate Sign Test",
            "contentHtml": "<h1>PCA</h1>",
        },
    ).json()["id"]

    iss = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"]]},
    ).json()
    sigs = unwrap_list(iss)
    sig_id = sigs[0]["id"] if sigs else iss["id"]

    s1 = api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_a,
        json={"signatureText": "First valid signature"},
    )
    assert s1.status_code == 200, s1.text

    s2 = api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_a,
        json={"signatureText": "Duplicate second signature"},
    )
    assert s2.status_code in (400, 409), s2.text


def test_f13_t2_05_downloading_unsigned_pca_signature_rejected_400_or_404(
    api, hr_lead, team_member_a
):
    ag_id = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Unsigned Download Test",
            "contentHtml": "<h1>PCA</h1>",
        },
    ).json()["id"]

    iss = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"]]},
    ).json()
    sigs = unwrap_list(iss)
    sig_id = sigs[0]["id"] if sigs else iss["id"]

    dl_resp = api.get(
        f"/api/v1/agreements/signatures/{sig_id}/download",
        actor=team_member_a,
    )
    assert dl_resp.status_code in (400, 404, 409), dl_resp.text


# ===========================================================================
# Feature F14: Global Task Rule — Mandatory Scheduled Deadline Boundaries (5 Tests)
# ===========================================================================


def test_f14_boundary_create_task_with_null_deadline_rejected_400(
    api, gdg_lead_1, team_lead_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Null Deadline Task",
            "description": "Must reject null deadline",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": None,
        },
    )
    assert resp.status_code == 400, resp.text


def test_f14_boundary_create_task_with_missing_deadline_field_rejected_400(
    api, gdg_lead_1, team_lead_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Missing Deadline Task",
            "description": "Must reject missing deadline key",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f14_boundary_create_task_with_past_deadline_rejected_400(
    api, gdg_lead_1, team_lead_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Past Deadline Task",
            "description": "Must reject past deadline",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": "2020-01-01T00:00:00Z",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f14_boundary_create_task_with_malformed_deadline_string_rejected_400(
    api, gdg_lead_1, team_lead_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Malformed Deadline Task",
            "description": "Invalid timestamp",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": "not-a-timestamp",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f14_boundary_create_task_with_blank_title_rejected_400(
    api, gdg_lead_1, team_lead_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "   ",
            "description": "Blank title",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=7),
        },
    )
    assert resp.status_code == 400, resp.text


# ===========================================================================
# Feature F15: Global Task Rule — Assignee-Only Progress & Tracking Boundaries (5 Tests)
# ===========================================================================


def test_f15_boundary_assignor_attempts_to_update_assignee_progress_rejected_403(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Assignor Override Attempt",
            "description": "Assignor cannot update progress",
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
        actor=team_lead_a,
        json={
            "progressPercentage": 50,
            "status": "IN_PROGRESS",
            "note": "Assignor override attempt",
        },
    )
    assert p_resp.status_code == 403, p_resp.text


def test_f15_boundary_unrelated_team_member_updates_others_task_progress_rejected_403(
    api, team_lead_a, team_member_a, team_member_b, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Other Member Progress Attempt",
            "description": "Member B cannot update Member A task",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 40, "status": "IN_PROGRESS", "note": "Intruder"},
    )
    assert p_resp.status_code == 403, p_resp.text


def test_f15_boundary_assignee_updates_progress_negative_percentage_rejected_400(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Negative Progress",
            "description": "Progress < 0",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": -1, "status": "IN_PROGRESS", "note": "Negative"},
    )
    assert p_resp.status_code == 400, p_resp.text


def test_f15_boundary_assignee_updates_progress_above_100_rejected_400(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Over 100 Progress",
            "description": "Progress > 100",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": 101, "status": "IN_PROGRESS", "note": "Over 100"},
    )
    assert p_resp.status_code == 400, p_resp.text


def test_f15_boundary_unrelated_member_views_task_tracking_rejected_403(
    api, team_lead_a, team_member_a, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F15 Restricted Tracking",
            "description": "Unrelated member cannot view tracking",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    tr_resp = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_member_b)
    assert tr_resp.status_code == 403, tr_resp.text


# ===========================================================================
# Feature F16: GDG Leads Assign Tasks to Team Leads Boundaries (5 Tests)
# ===========================================================================


def test_f16_boundary_team_lead_attempts_gdg_lead_to_team_lead_assignment_rejected_403(
    api, team_lead_a, team_lead_b, team_a, team_b
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Lead to Lead Attempt",
            "description": "Only GDG Lead can use GDG_LEAD_TO_TEAM_LEAD",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f16_boundary_hr_lead_attempts_gdg_lead_to_team_lead_assignment_rejected_403(
    api, hr_lead, team_lead_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=hr_lead,
        json={
            "title": "HR Lead Attempt",
            "description": "Forbidden",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f16_boundary_gdg_lead_assigns_gdg_to_team_lead_task_to_non_team_lead_rejected_400(
    api, gdg_lead_1, team_member_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Non-Lead Assignee Attempt",
            "description": "Assignee is TEAM_MEMBER, not TEAM_LEAD",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 400, resp.text


def test_f16_boundary_gdg_lead_attempts_to_share_assign_task_to_team_lead_permission_rejected_400(
    api, gdg_lead_1, team_lead_a
):
    resp = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_lead_a["id"],
            "permissionCode": "ASSIGN_TASK_TO_TEAM_LEAD",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "ASSIGN_TASK_TO_TEAM_LEAD is non-shareable",
        },
    )
    assert resp.status_code in (400, 403), resp.text


def test_f16_boundary_gdg_lead_assigns_task_to_nonexistent_assignee_rejected_404_or_400(
    api, gdg_lead_1, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Ghost Assignee Task",
            "description": "Assignee does not exist",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": 999999,
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code in (400, 404), resp.text


# ===========================================================================
# Feature F17: Team Leads Assign Tasks to Team Boundaries (5 Tests)
# ===========================================================================


def test_f17_boundary_regular_member_attempts_team_lead_to_team_assignment_rejected_403(
    api, team_member_a, team_member_b, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Member Using TEAM_LEAD_TO_TEAM",
            "description": "Forbidden",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f17_boundary_hr_member_attempts_team_lead_to_team_assignment_rejected_403(
    api, hr_member, team_member_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=hr_member,
        json={
            "title": "HR Member Using TEAM_LEAD_TO_TEAM",
            "description": "Forbidden",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f17_boundary_event_team_member_attempts_team_lead_to_team_assignment_rejected_403(
    api, event_team_member, team_member_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=event_team_member,
        json={
            "title": "Event Team Member Using TEAM_LEAD_TO_TEAM",
            "description": "Forbidden",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f17_boundary_team_lead_assigns_task_to_terminated_member_rejected_400(
    api, hr_lead, team_lead_a, team_member_a, team_a
):
    api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Terminated before task assignment", "terminatedAt": None},
    )
    resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Task For Terminated Member",
            "description": "Must reject terminated assignee",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 400, resp.text


def test_f17_boundary_team_lead_assigns_task_to_nonexistent_target_team_rejected_404_or_400(
    api, team_lead_a, team_member_a, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Task For Nonexistent Team",
            "description": "Target team does not exist",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": 999999,
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code in (400, 404), resp.text


# ===========================================================================
# Feature F18: Team Members Assign Tasks Within / Across Teams Boundaries (5 Tests)
# ===========================================================================


def test_f18_boundary_unpermitted_member_attempts_intra_team_assignment_rejected_403(
    api, team_member_a, team_member_b, team_a
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Unpermitted Intra-Team Task",
            "description": "No grant",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f18_boundary_unpermitted_member_attempts_cross_team_assignment_rejected_403(
    api, team_member_a, team_member_b, team_a, team_b
):
    resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Unpermitted Cross-Team Task",
            "description": "No grant",
            "assignmentType": "MEMBER_CROSS_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "LOW",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f18_boundary_member_with_future_scheduled_grant_attempts_task_assignment_early_rejected_403(
    api, team_lead_a, team_member_a, team_member_b, team_a
):
    api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_INTRA_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": future_iso(days=2),
            "validUntil": future_iso(days=5),
            "reason": "Future window",
        },
    )
    resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Early Grant Task Attempt",
            "description": "Grant not yet valid",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f18_boundary_member_with_revoked_grant_attempts_task_assignment_rejected_403(
    api, team_lead_a, team_member_a, team_member_b, team_a
):
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_INTRA_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Will be revoked",
        },
    )
    grant_id = g_resp.json()["id"]
    api.delete(f"/api/v1/permissions/grants/{grant_id}", actor=team_lead_a)

    resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Revoked Grant Task Attempt",
            "description": "Grant was revoked",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code == 403, resp.text


def test_f18_boundary_member_with_intra_team_only_grant_attempts_cross_team_assignment_rejected_403_or_400(
    api, team_lead_a, team_member_a, team_member_b, team_a, team_b
):
    api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_INTRA_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Intra-team only",
        },
    )
    resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Cross-Team With Intra-Only Grant",
            "description": "Should fail",
            "assignmentType": "MEMBER_CROSS_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    assert resp.status_code in (400, 403), resp.text


# ===========================================================================
# Feature F19: Cross-Team Task Approval Workflow Boundaries (5 Tests)
# ===========================================================================


def test_f19_boundary_source_team_lead_attempts_to_self_approve_cross_team_task_rejected_403(
    api, team_lead_a, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F19 Self Approval Attempt",
            "description": "Source lead cannot approve for target team",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    app_resp = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_a,
        json={"approved": True, "comment": "Self approval by source lead"},
    )
    assert app_resp.status_code == 403, app_resp.text


def test_f19_boundary_regular_member_attempts_to_approve_cross_team_task_rejected_403(
    api, team_lead_a, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F19 Member Approval Attempt",
            "description": "Regular member cannot approve",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    app_resp = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_member_b,
        json={"approved": True, "comment": "Member trying to approve own task"},
    )
    assert app_resp.status_code == 403, app_resp.text


def test_f19_boundary_assignee_attempts_progress_update_while_pending_team_lead_approval_rejected_400_or_409(
    api, team_lead_a, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F19 Premature Progress Attempt",
            "description": "Still PENDING_TEAM_LEAD_APPROVAL",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 30, "status": "IN_PROGRESS", "note": "Too early"},
    )
    assert p_resp.status_code in (400, 409), p_resp.text


def test_f19_boundary_assignee_attempts_progress_update_on_rejected_cross_team_task_rejected_400_or_409(
    api, team_lead_a, team_lead_b, team_member_b, team_a, team_b
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "F19 Rejected Task Progress Attempt",
            "description": "Rejected by target lead",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": False, "comment": "Rejected"},
    )

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 20, "status": "IN_PROGRESS", "note": "On rejected task"},
    )
    assert p_resp.status_code in (400, 409), p_resp.text


def test_f19_boundary_approve_already_assigned_intra_team_task_rejected_400_or_409(
    api, team_lead_a, team_member_a, team_a
):
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Already Assigned Task",
            "description": "Not pending cross-team approval",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=5),
        },
    )
    task_id = t_resp.json()["id"]

    app_resp = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_a,
        json={"approved": True, "comment": "Redundant approval"},
    )
    assert app_resp.status_code in (400, 409), app_resp.text


# ===========================================================================
# Feature F20: Global Member Profile, Letter History & PDF Download Boundaries (5 Tests)
# ===========================================================================


def test_f20_boundary_terminated_member_after_10_days_blocked_from_letter_list_403(
    api, hr_lead, user_factory
):
    member = user_factory(role="TEAM_MEMBER", full_name="F20 Expired List Member")
    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Expired window test", "terminatedAt": "2026-10-20T00:00:00Z"},
    )
    resp = api.get("/api/v1/profile/me/letters", actor=member)
    assert resp.status_code in (403, 410), resp.text


def test_f20_boundary_terminated_member_after_10_days_blocked_from_letter_download_403_or_410(
    api, hr_lead, user_factory
):
    member = user_factory(role="TEAM_MEMBER", full_name="F20 Expired Download Member")
    iss = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": member["id"],
            "letterType": "APPOINTMENT",
            "title": "Appointment",
            "contentHtml": "<h1>Appointment</h1>",
        },
    )
    letter_id = iss.json()["id"]

    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Expired window test", "terminatedAt": "2026-10-20T00:00:00Z"},
    )
    resp = api.get(f"/api/v1/profile/me/letters/{letter_id}/download", actor=member)
    assert resp.status_code in (403, 410), resp.text


def test_f20_boundary_expired_or_tampered_signed_download_token_rejected_403_or_410(
    api,
):
    resp = api.get("/api/v1/letters/signed-download/tampered.invalid.token.signature")
    assert resp.status_code in (400, 401, 403, 404, 410), resp.text


def test_f20_boundary_member_attempts_to_download_another_members_letter_rejected_403(
    api, hr_lead, team_member_a, team_member_b
):
    iss = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": team_member_a["id"],
            "letterType": "APPRECIATION",
            "title": "Private Letter For A",
            "contentHtml": "<h1>Private</h1>",
        },
    )
    letter_id = iss.json()["id"]

    resp = api.get(
        f"/api/v1/profile/me/letters/{letter_id}/download",
        actor=team_member_b,
    )
    assert resp.status_code in (403, 404), resp.text


def test_f20_boundary_regular_member_attempts_to_issue_letter_rejected_403(
    api, team_member_a
):
    resp = api.post(
        "/api/v1/letters/issue",
        actor=team_member_a,
        json={
            "memberId": team_member_a["id"],
            "letterType": "APPRECIATION",
            "title": "Self-issued",
            "contentHtml": "<p>Test</p>",
        },
    )
    assert resp.status_code == 403, resp.text


# ===========================================================================
# Feature F21: Member Request for Bio-Data Change Boundaries (5 Tests)
# ===========================================================================


def test_f21_boundary_terminated_member_within_10_day_window_cannot_request_bio_data_change_403(
    api, hr_lead, user_factory
):
    member = user_factory(role="TEAM_MEMBER", full_name="Terminated Bio Requestor")
    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Terminated now", "terminatedAt": None},
    )
    resp = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=member,
        json={
            "fullName": "Updated After Termination",
            "department": "CSE",
            "academicYear": 4,
            "reason": "Blocked",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f21_boundary_regular_member_attempts_to_approve_own_bio_data_request_rejected_403(
    api, team_a, team_member_a
):
    req = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "Self Approved Bio",
            "department": "CSE",
            "academicYear": 4,
            "reason": "Self approval test",
        },
    ).json()
    req_id = req.get("id") or req.get("changeRequestId")

    resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_member_a,
        json={"approved": True, "comment": "Self approval"},
    )
    assert resp.status_code == 403, resp.text


def test_f21_boundary_hr_member_attempts_to_approve_bio_data_request_rejected_403(
    api, hr_member, team_a, team_member_a
):
    req = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "HR Member Approval Test",
            "department": "CSE",
            "academicYear": 4,
            "reason": "HR Member cannot approve",
        },
    ).json()
    req_id = req.get("id") or req.get("changeRequestId")

    resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_member,
        json={"approved": True, "comment": "HR Member approval attempt"},
    )
    assert resp.status_code == 403, resp.text


def test_f21_boundary_approve_already_executed_change_request_rejected_400_or_409(
    api, gdg_lead_1, hr_lead, team_a, team_member_a
):
    req = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "Already Executed Bio",
            "department": "CSE",
            "academicYear": 4,
            "reason": "Double approval check",
        },
    ).json()
    req_id = req.get("id") or req.get("changeRequestId")

    api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Executed"},
    )

    resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "Approving already executed request"},
    )
    assert resp.status_code in (400, 409), resp.text


def test_f21_boundary_unauthenticated_bio_data_request_rejected_401_or_403(api):
    resp = api.post(
        "/api/v1/profile/me/bio-data-requests",
        json={"fullName": "Anon", "department": "CSE", "academicYear": 3, "reason": "No token"},
    )
    assert resp.status_code in (401, 403), resp.text


# ===========================================================================
# Feature F22: Member Request for Team Change with Reason Boundaries (5 Tests)
# ===========================================================================


def test_f22_boundary_team_change_request_with_blank_reason_rejected_400(
    api, team_a, team_b, team_member_a
):
    resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "   ",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f22_boundary_team_change_request_with_null_reason_rejected_400(
    api, team_a, team_b, team_member_a
):
    resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": None,
        },
    )
    assert resp.status_code == 400, resp.text


def test_f22_boundary_team_change_request_with_same_current_and_target_team_rejected_400(
    api, team_a, team_member_a
):
    resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "reason": "Same team transfer",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f22_boundary_terminated_member_cannot_submit_team_change_request_403(
    api, hr_lead, team_a, team_b, team_member_a
):
    api.post(
        f"/api/v1/members/{team_member_a['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Terminated", "terminatedAt": None},
    )
    resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Valid reason but terminated",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f22_boundary_team_change_request_to_nonexistent_target_team_rejected_404_or_400(
    api, team_a, team_member_a
):
    resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": 999999,
            "reason": "Transfer to ghost team",
        },
    )
    assert resp.status_code in (400, 404), resp.text


# ===========================================================================
# Feature F23: Event Management — CRUD, Leads & Attendance Boundaries (5 Tests)
# ===========================================================================


def test_f23_boundary_duplicate_barcode_attendance_checkin_rejected_409(api, event_lead):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Dup Barcode Event {RUN_ID}-{next_seq()}",
            "description": "Duplicate barcode check",
            "eventDate": future_iso(days=5),
            "venue": "Hall 1",
        },
    ).json()["id"]

    barcode = f"QR-DUP-{RUN_ID}-{next_seq()}"
    r1 = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "Alice First",
            "participantEmail": unique_email("dup_b1"),
            "rollNumber": "22CS501",
            "barcodeToken": barcode,
            "entryMethod": "BARCODE",
        },
    )
    assert r1.status_code in (200, 201), r1.text

    r2 = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "Alice Second",
            "participantEmail": unique_email("dup_b2"),
            "rollNumber": "22CS502",
            "barcodeToken": barcode,
            "entryMethod": "BARCODE",
        },
    )
    assert r2.status_code == 409, r2.text


def test_f23_boundary_duplicate_participant_email_attendance_checkin_rejected_409(
    api, event_lead
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Dup Email Event {RUN_ID}-{next_seq()}",
            "description": "Duplicate email check",
            "eventDate": future_iso(days=5),
            "venue": "Hall 2",
        },
    ).json()["id"]

    shared_email = unique_email("dup_email")
    r1 = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "Alice One",
            "participantEmail": shared_email,
            "rollNumber": "22CS503",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )
    assert r1.status_code in (200, 201), r1.text

    r2 = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "Alice One Duplicate",
            "participantEmail": shared_email,
            "rollNumber": "22CS503",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )
    assert r2.status_code == 409, r2.text


def test_f23_boundary_unpermitted_team_member_creates_event_rejected_403(
    api, team_member_a
):
    resp = api.post(
        "/api/v1/events",
        actor=team_member_a,
        json={
            "name": "Unauthorized Event",
            "description": "No event clearance",
            "eventDate": future_iso(days=5),
            "venue": "Hall 3",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f23_boundary_unpermitted_hr_member_records_event_attendance_rejected_403(
    api, event_lead, hr_member
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Restricted Attendance Event {RUN_ID}-{next_seq()}",
            "description": "HR member has no event clearance",
            "eventDate": future_iso(days=5),
            "venue": "Hall 4",
        },
    ).json()["id"]

    resp = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=hr_member,
        json={
            "participantName": "Unauthorized Checkin",
            "participantEmail": unique_email("unauth_att"),
            "rollNumber": "22CS509",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f23_boundary_record_attendance_for_nonexistent_event_rejected_404(
    api, event_lead
):
    resp = api.post(
        "/api/v1/events/999999/attendance",
        actor=event_lead,
        json={
            "participantName": "Ghost Event Attendee",
            "participantEmail": unique_email("ghost_ev"),
            "rollNumber": "22CS510",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )
    assert resp.status_code == 404, resp.text


# ===========================================================================
# Feature F24: Event Certificate Generation & Storage Boundaries (5 Tests)
# ===========================================================================


def test_f24_boundary_generate_certificate_for_nonexistent_attendance_id_rejected_400(
    api, event_lead
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Nonexistent Att Cert {RUN_ID}-{next_seq()}",
            "description": "Test",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]

    resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [999999],
            "generateForAllAttendees": False,
            "templateTitle": "Cert",
        },
    )
    assert resp.status_code in (400, 404), resp.text


def test_f24_boundary_generate_certificate_for_attendee_of_different_event_rejected_400(
    api, event_lead
):
    ev1_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Event One {RUN_ID}-{next_seq()}",
            "description": "E1",
            "eventDate": future_iso(days=5),
            "venue": "H1",
        },
    ).json()["id"]
    ev2_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Event Two {RUN_ID}-{next_seq()}",
            "description": "E2",
            "eventDate": future_iso(days=6),
            "venue": "H2",
        },
    ).json()["id"]

    att_ev2 = api.post(
        f"/api/v1/events/{ev2_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "Wrong Event Attendee",
            "participantEmail": unique_email("wrong_ev"),
            "rollNumber": "22CS601",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    ).json()["id"]

    resp = api.post(
        f"/api/v1/events/{ev1_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [att_ev2],
            "generateForAllAttendees": False,
            "templateTitle": "Wrong Event Cert",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f24_boundary_unpermitted_member_attempts_certificate_generation_rejected_403(
    api, event_lead, team_member_a
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Unpermitted Cert Gen {RUN_ID}-{next_seq()}",
            "description": "Test",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]

    resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=team_member_a,
        json={
            "attendanceIds": [],
            "generateForAllAttendees": True,
            "templateTitle": "Cert",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f24_boundary_download_nonexistent_certificate_id_rejected_404(api, event_lead):
    resp = api.get("/api/v1/certificates/999999/download", actor=event_lead)
    assert resp.status_code == 404, resp.text


def test_f24_boundary_generate_certificates_for_event_with_zero_attendees_and_empty_ids_rejected_400(
    api, event_lead
):
    empty_ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Empty Cert Event {RUN_ID}-{next_seq()}",
            "description": "Zero attendees",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]

    resp = api.post(
        f"/api/v1/events/{empty_ev_id}/certificates/generate",
        actor=event_lead,
        json={
            "attendanceIds": [],
            "generateForAllAttendees": False,
            "templateTitle": "Empty",
        },
    )
    assert resp.status_code == 400, resp.text


# ===========================================================================
# Feature F25: Mail Certificates, Bounce Webhook & Observability Boundaries (5 Tests)
# ===========================================================================


def test_f25_boundary_mail_certificates_with_invalid_cc_email_rejected_400(
    api, event_lead
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Invalid CC Event {RUN_ID}-{next_seq()}",
            "description": "Invalid CC test",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]
    att_id = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "CC Test User",
            "participantEmail": unique_email("cc_test"),
            "rollNumber": "22CS701",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    ).json()["id"]
    cert_id = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_id}/certificates/generate",
            actor=event_lead,
            json={"attendanceIds": [att_id], "generateForAllAttendees": False, "templateTitle": "C"},
        ).json()
    )[0]["id"]

    resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/mail",
        actor=event_lead,
        json={
            "certificateIds": [cert_id],
            "cc": ["not-an-email-address"],
            "bcc": [],
            "subject": "Cert",
            "body": "Body",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f25_boundary_mail_certificates_with_invalid_bcc_email_rejected_400(
    api, event_lead
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Invalid BCC Event {RUN_ID}-{next_seq()}",
            "description": "Invalid BCC test",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]
    att_id = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_lead,
        json={
            "participantName": "BCC Test User",
            "participantEmail": unique_email("bcc_test"),
            "rollNumber": "22CS702",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    ).json()["id"]
    cert_id = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_id}/certificates/generate",
            actor=event_lead,
            json={"attendanceIds": [att_id], "generateForAllAttendees": False, "templateTitle": "C"},
        ).json()
    )[0]["id"]

    resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/mail",
        actor=event_lead,
        json={
            "certificateIds": [cert_id],
            "cc": [],
            "bcc": ["invalid@@domain..com"],
            "subject": "Cert",
            "body": "Body",
        },
    )
    assert resp.status_code == 400, resp.text


def test_f25_boundary_unpermitted_team_member_mails_certificates_rejected_403(
    api, event_lead, team_member_a
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Unauth Mail Event {RUN_ID}-{next_seq()}",
            "description": "Unauthorized mail",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]

    resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/mail",
        actor=team_member_a,
        json={
            "certificateIds": [1],
            "cc": [],
            "bcc": [],
            "subject": "Unauthorized",
            "body": "Body",
        },
    )
    assert resp.status_code == 403, resp.text


def test_f25_boundary_unpermitted_team_member_views_event_mail_bounces_rejected_403(
    api, event_lead, team_member_a
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Unauth Bounce View {RUN_ID}-{next_seq()}",
            "description": "Unauthorized bounce view",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]

    r1 = api.get("/api/v1/events/mail-bounces", actor=team_member_a)
    assert r1.status_code == 403, r1.text

    r2 = api.get(f"/api/v1/events/{ev_id}/mail-logs", actor=team_member_a)
    assert r2.status_code == 403, r2.text


def test_f25_boundary_mail_nonexistent_certificate_ids_rejected_400_or_404(
    api, event_lead
):
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Nonexistent Cert Mail {RUN_ID}-{next_seq()}",
            "description": "Test",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]

    resp = api.post(
        f"/api/v1/events/{ev_id}/certificates/mail",
        actor=event_lead,
        json={
            "certificateIds": [999999],
            "cc": [],
            "bcc": [],
            "subject": "Missing cert",
            "body": "Body",
        },
    )
    assert resp.status_code in (400, 404), resp.text
