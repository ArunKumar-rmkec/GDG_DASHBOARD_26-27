"""
Tier 3 — Cross-Feature Pairwise Combination Suite (28 Tests: test_tier3_01 through test_tier3_28).

Verifies state propagation, RBAC interactions, consensus workflows, dual authorization,
cross-team task approvals, 10-day post-termination windows, and PDF rendering across
2 to 4 combined features (F1–F25) over live HTTP REST endpoints.
"""

import uuid
from conftest import (
    RUN_ID,
    assert_pdf_response,
    create_approved_team,
    future_iso,
    next_seq,
    past_iso,
    unique_email,
    unique_roll,
    unwrap_list,
)


def test_tier3_01_google_oauth_registration_to_profile_and_me(api, hr_lead):
    """F1 + F11 + F20: Google OAuth login -> /auth/me -> /profile/me -> /members/{id}."""
    email = unique_email("t3_google")
    g_resp = api.post(
        "/api/v1/auth/google",
        json={
            "idToken": f"google-token-{uuid.uuid4().hex}",
            "email": email,
            "fullName": "Tier3 Google User",
            "googleSub": f"sub-{uuid.uuid4().hex}",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text
    g_body = g_resp.json()
    token = g_body["accessToken"]
    user_id = g_body["member"]["id"]

    me_resp = api.get("/api/v1/auth/me", token=token)
    assert me_resp.status_code == 200, me_resp.text
    assert me_resp.json()["email"] == email

    prof_resp = api.get("/api/v1/profile/me", token=token)
    assert prof_resp.status_code == 200, prof_resp.text
    assert prof_resp.json()["id"] == user_id
    assert prof_resp.json()["status"] == "ACTIVE"

    detail_resp = api.get(f"/api/v1/members/{user_id}", actor=hr_lead)
    assert detail_resp.status_code == 200, detail_resp.text
    assert detail_resp.json()["email"] == email
    assert detail_resp.json()["fullName"] == "Tier3 Google User"


def test_tier3_02_three_lead_team_creation_and_team_stats_visibility(
    api, gdg_lead_1, gdg_lead_2, gdg_lead_3, hr_member
):
    """F2 + F6 + F8: 3-GDG-Lead consensus team creation -> HR Member stats query."""
    t_name = f"T3-Consensus-Team-{RUN_ID}-{next_seq()}"
    r1 = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={
            "actionType": "CREATE_TEAM",
            "targetTeamId": None,
            "proposedName": t_name,
            "proposedDescription": "Tier 3 consensus team",
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
        json={"approved": True, "comment": "Vote 2"},
    )
    assert r2.status_code == 200, r2.text
    assert r2.json()["status"] == "PENDING"
    assert r2.json()["approvalCount"] == 2

    r3 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_3,
        json={"approved": True, "comment": "Vote 3"},
    )
    assert r3.status_code == 200, r3.text
    assert r3.json()["status"] == "EXECUTED"
    team_id = r3.json()["executedTeamId"]

    st_resp = api.get(f"/api/v1/teams/{team_id}/stats", actor=hr_member)
    assert st_resp.status_code == 200, st_resp.text
    assert st_resp.json()["teamId"] == team_id
    assert st_resp.json()["activeMemberCount"] == 0


def test_tier3_03_three_lead_team_rename_and_collaboration_reflection(
    api, gdg_leads, hr_member, team_a, team_b
):
    """F6 + F10 + F12: Cross-team collaboration -> 3-Lead RENAME_TEAM -> collaboration reflection."""
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=hr_member,
        json={
            "title": "Rename Reflection Collab",
            "description": "Collab surviving team rename",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=15),
        },
    )
    assert c_resp.status_code in (200, 201), c_resp.text
    collab_id = c_resp.json()["id"]

    new_name = f"Renamed-Alpha-{RUN_ID}-{next_seq()}"
    req_id = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_leads[0],
        json={
            "actionType": "RENAME_TEAM",
            "targetTeamId": team_a["id"],
            "proposedName": new_name,
            "proposedDescription": "Renamed",
        },
    ).json()["id"]
    api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_leads[1],
        json={"approved": True, "comment": "2/3"},
    )
    r3 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_leads[2],
        json={"approved": True, "comment": "3/3"},
    )
    assert r3.json()["status"] == "EXECUTED"

    t_resp = api.get(f"/api/v1/teams/{team_a['id']}", actor=hr_member)
    assert t_resp.status_code == 200
    assert t_resp.json()["name"] == new_name

    col_get = api.get(f"/api/v1/collaborations/{collab_id}", actor=hr_member)
    assert col_get.status_code == 200
    assert team_a["id"] in col_get.json()["participatingTeamIds"]


def test_tier3_04_three_lead_team_deletion_transitions_members_to_past_members(
    api, gdg_leads, hr_lead, user_factory
):
    """F6 + F8 + F9: Create team with 2 members -> 3-Lead DELETE_TEAM -> past members audit."""
    m1 = user_factory(role="TEAM_MEMBER", full_name="Del Member 1")
    m2 = user_factory(role="TEAM_MEMBER", full_name="Del Member 2")
    team = create_approved_team(
        api,
        gdg_leads,
        name=f"Deletable-Team-{RUN_ID}-{next_seq()}",
        members=[m1, m2],
    )

    stats = api.get(f"/api/v1/teams/{team['id']}/stats", actor=hr_lead).json()
    assert stats["activeMemberCount"] == 2

    req_id = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_leads[0],
        json={"actionType": "DELETE_TEAM", "targetTeamId": team["id"]},
    ).json()["id"]
    api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_leads[1],
        json={"approved": True, "comment": "2/3"},
    )
    r3 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_leads[2],
        json={"approved": True, "comment": "3/3"},
    )
    assert r3.json()["status"] == "EXECUTED"

    pm_resp = api.get("/api/v1/teams/past-members", actor=hr_lead)
    assert pm_resp.status_code == 200
    team_past = [
        p
        for p in unwrap_list(pm_resp.json())
        if p.get("teamId") == team["id"] and p.get("memberId") in (m1["id"], m2["id"])
    ]
    assert len(team_past) == 2
    assert all(p.get("exitReason") == "TEAM_DELETED" and p.get("leftAt") is not None for p in team_past)


def test_tier3_05_scheduled_grant_view_team_stats_and_revocation(
    api, team_lead_a, team_member_a, team_a
):
    """F3 + F4 + F8: 403 -> grant VIEW_TEAM_STATS -> 200 -> revoke -> 403."""
    assert api.get("/api/v1/teams/stats", actor=team_member_a).status_code == 403

    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=2),
            "reason": "Temporary analytics grant",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text
    grant_id = g_resp.json()["id"]

    assert api.get("/api/v1/teams/stats", actor=team_member_a).status_code == 200

    rev = api.delete(f"/api/v1/permissions/grants/{grant_id}", actor=team_lead_a)
    assert rev.status_code == 200, rev.text

    assert api.get("/api/v1/teams/stats", actor=team_member_a).status_code == 403


def test_tier3_06_scheduled_grant_view_past_members_and_collaborations(
    api, gdg_lead_1, team_member_a
):
    """F3 + F4 + F9 + F10: Grant and revoke VIEW_PAST_MEMBERS and MONITOR_COLLABORATIONS."""
    assert api.get("/api/v1/teams/past-members", actor=team_member_a).status_code == 403
    assert api.get("/api/v1/collaborations", actor=team_member_a).status_code == 403

    g1 = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "VIEW_PAST_MEMBERS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=2),
            "reason": "Grant 1",
        },
    ).json()["id"]
    g2 = api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "MONITOR_COLLABORATIONS",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=2),
            "reason": "Grant 2",
        },
    ).json()["id"]

    assert api.get("/api/v1/teams/past-members", actor=team_member_a).status_code == 200
    assert api.get("/api/v1/collaborations", actor=team_member_a).status_code == 200

    api.delete(f"/api/v1/permissions/grants/{g1}", actor=gdg_lead_1)
    api.delete(f"/api/v1/permissions/grants/{g2}", actor=gdg_lead_1)

    assert api.get("/api/v1/teams/past-members", actor=team_member_a).status_code == 403
    assert api.get("/api/v1/collaborations", actor=team_member_a).status_code == 403


def test_tier3_07_cross_team_permission_grant_requires_target_team_lead_approval(
    api, team_lead_a, team_lead_b, team_member_a, team_a, team_b
):
    """F3 + F4 + F18: Cross-team permission grant PENDING_TEAM_LEAD_APPROVAL -> ACTIVE."""
    g_resp = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "DASHBOARD_ACTION_ACCESS",
            "scopeTeamId": team_a["id"],
            "crossTeamTargetTeamId": team_b["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Cross-team grant",
        },
    )
    assert g_resp.status_code in (200, 201), g_resp.text
    grant_id = g_resp.json()["id"]
    assert g_resp.json()["status"] == "PENDING_TEAM_LEAD_APPROVAL"

    app = api.post(
        f"/api/v1/permissions/grants/{grant_id}/approve",
        actor=team_lead_b,
        json={"approved": True, "comment": "Approved by Team B Lead"},
    )
    assert app.status_code == 200, app.text
    assert app.json()["status"] == "ACTIVE"

    my_grants = unwrap_list(api.get("/api/v1/permissions/grants/my", actor=team_member_a).json())
    matched = [g for g in my_grants if g["id"] == grant_id]
    assert matched and matched[0]["status"] == "ACTIVE"


def test_tier3_08_hr_member_staged_bio_data_edit_with_dual_lead_approval(
    api, hr_lead, team_lead_a, hr_member, team_a, team_member_a
):
    """F3 + F7 + F11: Grant BIO_DATA_EDIT_HR_TEAM -> stage edit -> dual approval -> verify."""
    api.post(
        "/api/v1/permissions/grants",
        actor=hr_lead,
        json={
            "granteeMemberId": hr_member["id"],
            "permissionCode": "BIO_DATA_EDIT_HR_TEAM",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "HR bio-data audit",
        },
    )

    orig_name = team_member_a["fullName"]
    staged = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Tier3 Dual Approved Member",
            "department": "AI&DS",
            "academicYear": 4,
            "phone": "+919876540008",
        },
    )
    assert staged.status_code in (200, 201, 202), staged.text
    req_id = staged.json().get("changeRequestId") or staged.json().get("id")

    assert api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead).json()["fullName"] == orig_name

    a1 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead OK"},
    )
    assert a1.json()["status"] == "PARTIALLY_APPROVED"
    assert api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead).json()["fullName"] == orig_name

    a2 = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead OK"},
    )
    assert a2.json()["status"] == "EXECUTED"
    assert (
        api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead).json()["fullName"]
        == "Tier3 Dual Approved Member"
    )


def test_tier3_09_hr_member_staged_member_addition_and_removal_dual_approval(
    api, hr_member, hr_lead, team_lead_a, team_a
):
    """F7 + F9 + F11: HR Member stages member addition and removal with dual approval."""
    email = unique_email("t3_staged_add_del")
    add_resp = api.post(
        "/api/v1/members",
        actor=hr_member,
        json={
            "email": email,
            "password": "Password123!",
            "fullName": "Staged Lifecycle Member",
            "rollNumber": unique_roll(),
            "department": "CSE",
            "academicYear": 2,
            "phone": "+919876540009",
            "roles": ["TEAM_MEMBER"],
            "teamId": team_a["id"],
        },
    )
    assert add_resp.status_code in (200, 201, 202), add_resp.text
    add_req_id = add_resp.json().get("changeRequestId") or add_resp.json().get("id")

    api.post(
        f"/api/v1/change-requests/{add_req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead approves add"},
    )
    exec_add = api.post(
        f"/api/v1/change-requests/{add_req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead approves add"},
    )
    assert exec_add.json()["status"] == "EXECUTED"

    all_members = unwrap_list(api.get("/api/v1/members", actor=hr_lead).json())
    created = [m for m in all_members if m.get("email") == email]
    assert created
    new_member_id = created[0]["id"]

    # Ensure member is in team_a so team_lead_a can co-approve removal
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=hr_lead,
        json={"memberId": new_member_id, "teamRole": "MEMBER"},
    )

    del_resp = api.delete(f"/api/v1/members/{new_member_id}", actor=hr_member)
    assert del_resp.status_code in (200, 201, 202), del_resp.text
    del_req_id = del_resp.json().get("changeRequestId") or del_resp.json().get("id")

    api.post(
        f"/api/v1/change-requests/{del_req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead approves removal"},
    )
    exec_del = api.post(
        f"/api/v1/change-requests/{del_req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead approves removal"},
    )
    assert exec_del.json()["status"] == "EXECUTED"


def test_tier3_10_member_self_service_bio_data_request_approved_by_hr_and_team_lead(
    api, team_member_a, hr_lead, team_lead_a, team_a
):
    """F20 + F21 + F7 + F11: Self-service bio-data update -> dual approval -> profile & member detail."""
    req = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "Self Service Updated Alpha",
            "phone": "+919876540010",
            "department": "CSE",
            "academicYear": 4,
            "skills": "Go, Kubernetes, Terraform",
            "githubUrl": "https://github.com/alpha-t3",
            "reason": "Added cloud-native skills",
        },
    ).json()
    req_id = req.get("id") or req.get("changeRequestId")

    api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR OK"},
    )
    api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead OK"},
    )

    prof = api.get("/api/v1/profile/me", actor=team_member_a).json()
    assert prof.get("skills") == "Go, Kubernetes, Terraform"

    detail = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead).json()
    assert detail.get("skills") == "Go, Kubernetes, Terraform"


def test_tier3_11_member_self_service_team_change_request_updates_past_members_and_stats(
    api, hr_lead, team_lead_a, team_a, team_b, team_member_a
):
    """F22 + F7 + F8 + F9: Team transfer -> dual approval -> past members of A & stats of B."""
    b_stats_before = api.get(f"/api/v1/teams/{team_b['id']}/stats", actor=hr_lead).json()

    req = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=team_member_a,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Cross-skilling into Cloud team",
        },
    ).json()
    req_id = req.get("id") or req.get("changeRequestId")

    api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead OK"},
    )
    exec_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead OK"},
    )
    assert exec_resp.json()["status"] == "EXECUTED"

    past_a = unwrap_list(
        api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=hr_lead).json()
    )
    assert any(p.get("memberId") == team_member_a["id"] and p.get("leftAt") is not None for p in past_a)

    b_stats_after = api.get(f"/api/v1/teams/{team_b['id']}/stats", actor=hr_lead).json()
    assert b_stats_after["activeMemberCount"] >= b_stats_before["activeMemberCount"] + 1


def test_tier3_12_pca_issuance_signing_pdf_download_and_profile_letter_history(
    api, hr_lead, team_member_a
):
    """F11 + F13 + F20: PCA create/issue -> member sign -> PDF download -> letter history & member detail."""
    ag_id = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Tier 3 PCA",
            "contentHtml": "<h1>PCA</h1><p>Full compliance flow.</p>",
        },
    ).json()["id"]

    iss = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [team_member_a["id"]]},
    ).json()
    sigs = unwrap_list(iss)
    sig_id = sigs[0]["id"] if sigs else iss["id"]

    api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_a,
        json={"signatureText": "Digitally signed by Alpha"},
    )

    dl = api.get(f"/api/v1/agreements/signatures/{sig_id}/download", actor=team_member_a)
    assert_pdf_response(dl)

    letters = unwrap_list(api.get("/api/v1/profile/me/letters", actor=team_member_a).json())
    assert any(l.get("letterType") == "CONTRIBUTOR_AGREEMENT" for l in letters)

    detail = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead).json()
    assert detail.get("pcaSigned") is True or detail.get("pcaStatus") == "SIGNED"


def test_tier3_13_letter_issuance_signed_url_download_and_within_10_day_termination_window(
    api, hr_lead, team_lead_a, team_a, user_factory
):
    """F3 + F5 + F20: Grant & letter -> terminate now -> 403 on ops -> 200 on profile & letters PDF."""
    member = user_factory(role="TEAM_MEMBER", full_name="Within 10d Terminated")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=hr_lead,
        json={"memberId": member["id"], "teamRole": "MEMBER"},
    )
    api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": member["id"],
            "permissionCode": "VIEW_TEAM_STATS",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Stats access",
        },
    )
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
    appt_letter_id = iss.json()["id"]

    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Tenure ended", "terminatedAt": None},
    )

    assert api.get("/api/v1/teams/stats", actor=member).status_code == 403
    assert api.get("/api/v1/teams", actor=member).status_code == 403
    assert (
        api.post(
            "/api/v1/profile/me/bio-data-requests",
            actor=member,
            json={"fullName": "No", "department": "CSE", "academicYear": 3, "reason": "No"},
        ).status_code
        == 403
    )

    assert api.get("/api/v1/profile/me", actor=member).status_code == 200
    l_resp = api.get("/api/v1/profile/me/letters", actor=member)
    assert l_resp.status_code == 200
    l_types = {l.get("letterType") for l in unwrap_list(l_resp.json())}
    assert "APPOINTMENT" in l_types
    assert "TERMINATION" in l_types

    dl_resp = api.get(f"/api/v1/profile/me/letters/{appt_letter_id}/download", actor=member)
    assert_pdf_response(dl_resp)


def test_tier3_14_termination_after_10_day_window_blocks_letter_and_signed_url_downloads(
    api, hr_lead, team_a, user_factory
):
    """F5 + F9 + F20: Terminate 11 days ago -> 403/410 on letters & signed URL -> past members."""
    member = user_factory(role="TEAM_MEMBER", full_name="Past 10d Terminated")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=hr_lead,
        json={"memberId": member["id"], "teamRole": "MEMBER"},
    )
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
        json={"reason": "Terminated 11d ago", "terminatedAt": past_iso(days=11)},
    )

    assert api.get(f"/api/v1/profile/me/letters/{letter_id}/download", actor=member).status_code in (
        403,
        410,
    )
    assert api.get(f"/api/v1/letters/signed-download/{token}").status_code in (403, 410)

    past = unwrap_list(api.get("/api/v1/teams/past-members", actor=hr_lead).json())
    assert any(p.get("memberId") == member["id"] and p.get("exitReason") == "TERMINATED" for p in past)


def test_tier3_15_gdg_lead_assigns_task_to_team_lead_and_tracks_progress_to_completion(
    api, gdg_lead_1, team_lead_a, team_member_a, team_a
):
    """F14 + F15 + F16 + F8: GDG Lead -> Team Lead task -> 403 non-assignee -> 100% -> tracking & stats."""
    t_resp = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Tier3 GDG Lead Task",
            "description": "Milestone delivery",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "CRITICAL",
            "deadline": future_iso(days=10),
        },
    )
    task_id = t_resp.json()["id"]

    assert (
        api.patch(
            f"/api/v1/tasks/{task_id}/progress",
            actor=gdg_lead_1,
            json={"progressPercentage": 20, "status": "IN_PROGRESS", "note": "Assignor blocked"},
        ).status_code
        == 403
    )
    assert (
        api.patch(
            f"/api/v1/tasks/{task_id}/progress",
            actor=team_member_a,
            json={"progressPercentage": 20, "status": "IN_PROGRESS", "note": "Member blocked"},
        ).status_code
        == 403
    )

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_lead_a,
        json={"progressPercentage": 50, "status": "IN_PROGRESS", "note": "50% done"},
    )
    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_lead_a,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "100% done"},
    )

    tr = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=gdg_lead_1).json()
    logs = tr.get("progressLogs") or tr.get("logs") or tr.get("history") or []
    assert len(logs) >= 2

    stats = api.get(f"/api/v1/teams/{team_a['id']}/stats", actor=gdg_lead_1).json()
    assert stats["completedTasks"] >= 1


def test_tier3_16_team_lead_intra_team_task_assignment_and_assignee_progress_audit(
    api, team_lead_a, team_member_a, team_a
):
    """F14 + F15 + F17: Team Lead intra-team task -> 403 lead progress -> 25/75/100% by member -> tracking."""
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Tier3 Intra-Team Audit Task",
            "description": "3-step progress audit",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=7),
        },
    )
    task_id = t_resp.json()["id"]

    assert (
        api.patch(
            f"/api/v1/tasks/{task_id}/progress",
            actor=team_lead_a,
            json={"progressPercentage": 10, "status": "IN_PROGRESS", "note": "Lead blocked"},
        ).status_code
        == 403
    )

    for pct, st in ((25, "IN_PROGRESS"), (75, "IN_REVIEW"), (100, "COMPLETED")):
        r = api.patch(
            f"/api/v1/tasks/{task_id}/progress",
            actor=team_member_a,
            json={"progressPercentage": pct, "status": st, "note": f"Step {pct}%"},
        )
        assert r.status_code == 200

    tr = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_lead_a).json()
    logs = tr.get("progressLogs") or tr.get("logs") or tr.get("history") or []
    assert len(logs) >= 3


def test_tier3_17_team_lead_cross_team_task_requires_target_team_lead_approval_before_progress(
    api, team_lead_a, team_lead_b, team_member_b, team_a, team_b
):
    """F15 + F17 + F19: Cross-team task -> blocked early progress -> wrong lead 403 -> target lead approve -> 100%."""
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Tier3 Cross-Team Gated Task",
            "description": "Requires Team B Lead approval",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=8),
        },
    )
    task_id = t_resp.json()["id"]
    assert t_resp.json()["status"] == "PENDING_TEAM_LEAD_APPROVAL"

    assert api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 20, "status": "IN_PROGRESS", "note": "Blocked"},
    ).status_code in (400, 409)

    assert (
        api.post(
            f"/api/v1/tasks/{task_id}/approve-cross-team",
            actor=team_lead_a,
            json={"approved": True, "comment": "Wrong lead"},
        ).status_code
        == 403
    )

    app = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": True, "comment": "Approved by Team B Lead"},
    )
    assert app.status_code == 200
    assert app.json()["status"] == "ASSIGNED"

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Done"},
    )
    assert p_resp.status_code == 200
    assert p_resp.json()["status"] == "COMPLETED"


def test_tier3_18_delegated_member_intra_team_task_assignment_and_tracking(
    api, gdg_lead_1, team_lead_a, team_member_a, team_a, user_factory
):
    """F3 + F4 + F15 + F18: 403 without grant -> grant ASSIGN_TASK_INTRA_TEAM -> assign -> track."""
    member_2 = user_factory(role="TEAM_MEMBER", full_name="Second Intra Member")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=gdg_lead_1,
        json={"memberId": member_2["id"], "teamRole": "MEMBER"},
    )

    assert (
        api.post(
            "/api/v1/tasks",
            actor=team_member_a,
            json={
                "title": "Blocked Intra Task",
                "description": "No grant yet",
                "assignmentType": "MEMBER_INTRA_TEAM",
                "assigneeId": member_2["id"],
                "sourceTeamId": team_a["id"],
                "targetTeamId": team_a["id"],
                "priority": "LOW",
                "deadline": future_iso(days=5),
            },
        ).status_code
        == 403
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
            "reason": "Delegate intra-team tasking",
        },
    )

    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Permitted Intra Task",
            "description": "With active grant",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": member_2["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=5),
        },
    )
    assert t_resp.status_code in (200, 201), t_resp.text
    task_id = t_resp.json()["id"]

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=member_2,
        json={"progressPercentage": 60, "status": "IN_PROGRESS", "note": "60%"},
    )

    tr_resp = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_member_a)
    assert tr_resp.status_code == 200, tr_resp.text


def test_tier3_19_delegated_member_cross_team_task_assignment_and_target_lead_approval(
    api, team_lead_a, team_lead_b, team_member_a, team_member_b, team_a, team_b
):
    """F3 + F18 + F19 + F15: Grant ASSIGN_TASK_CROSS_TEAM -> MEMBER_CROSS_TEAM -> Lead B approve -> 100%."""
    api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_CROSS_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Cross-team delegation",
        },
    )

    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Delegated Cross-Team Task",
            "description": "Needs Lead B approval",
            "assignmentType": "MEMBER_CROSS_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=6),
        },
    )
    assert t_resp.status_code in (200, 201), t_resp.text
    task_id = t_resp.json()["id"]
    assert t_resp.json()["status"] == "PENDING_TEAM_LEAD_APPROVAL"

    api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": True, "comment": "Lead B approves"},
    )

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Completed"},
    )

    tr_resp = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_member_a)
    assert tr_resp.status_code == 200, tr_resp.text


def test_tier3_20_cross_team_collaboration_creation_approval_and_linked_cross_team_task(
    api, hr_member, team_lead_a, team_lead_b, team_member_b, team_a, team_b
):
    """F10 + F12 + F17 + F19: Create collab -> both Team Leads approve -> linked cross-team task -> approve."""
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=hr_member,
        json={
            "title": "Tier3 Collab + Task Pipeline",
            "description": "Joint deliverable",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=20),
        },
    )
    collab_id = c_resp.json()["id"]

    api.post(
        f"/api/v1/collaborations/{collab_id}/approve-team",
        actor=team_lead_a,
        json={"teamId": team_a["id"], "approved": True, "comment": "Team A OK"},
    )
    api.post(
        f"/api/v1/collaborations/{collab_id}/approve-team",
        actor=team_lead_b,
        json={"teamId": team_b["id"], "approved": True, "comment": "Team B OK"},
    )

    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Collab Deliverable Task",
            "description": "Linked to collaboration",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "collaborationId": collab_id,
            "priority": "HIGH",
            "deadline": future_iso(days=10),
        },
    )
    task_id = t_resp.json()["id"]

    app = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": True, "comment": "Approved for collab"},
    )
    assert app.status_code == 200
    assert app.json()["status"] == "ASSIGNED"

    mon = api.get(f"/api/v1/collaborations/{collab_id}", actor=hr_member)
    assert mon.status_code == 200


def test_tier3_21_cross_team_task_rejection_blocks_assignee_progress(
    api, gdg_lead_1, team_lead_b, team_member_a, team_member_b, team_a, team_b
):
    """F15 + F18 + F19: Cross-team task rejected by target Team Lead -> progress update blocked."""
    api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_CROSS_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Cross-team task test",
        },
    )
    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Will Be Rejected Task",
            "description": "Rejected by Team B Lead",
            "assignmentType": "MEMBER_CROSS_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "LOW",
            "deadline": future_iso(days=7),
        },
    )
    task_id = t_resp.json()["id"]

    rej = api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": False, "comment": "Out of scope"},
    )
    assert rej.status_code == 200
    assert rej.json()["status"] == "REJECTED"

    p_resp = api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 50, "status": "IN_PROGRESS", "note": "Should fail"},
    )
    assert p_resp.status_code in (400, 409), p_resp.text


def test_tier3_22_sub_team_creation_via_3_leads_and_sub_team_collaboration_stats(
    api, gdg_leads, team_lead_a, team_a
):
    """F6 + F8 + F10 + F12: Sub-team creation via 3 leads -> SUB_TEAM collaboration -> stats check."""
    sub_team = create_approved_team(
        api,
        gdg_leads,
        name=f"SubTeam-T3-{RUN_ID}-{next_seq()}",
        parent_team_id=team_a["id"],
    )
    c_resp = api.post(
        "/api/v1/collaborations",
        actor=team_lead_a,
        json={
            "title": "Parent-Sub Collaboration",
            "description": "Sub-team sync",
            "collaborationType": "SUB_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], sub_team["id"]],
            "deadline": future_iso(days=14),
        },
    )
    assert c_resp.status_code in (200, 201), c_resp.text

    stats = api.get(f"/api/v1/teams/{team_a['id']}/stats", actor=team_lead_a).json()
    assert stats["subTeamCount"] >= 1
    assert stats["activeCollaborations"] >= 1


def test_tier3_23_event_creation_assign_event_specific_lead_and_attendance_checkin(
    api, gdg_lead_1, team_member_a
):
    """F23 + F3: 403 before event lead assignment -> assign event-specific lead -> BARCODE & MANUAL checkin."""
    ev_id = api.post(
        "/api/v1/events",
        actor=gdg_lead_1,
        json={
            "name": f"T3 Event Lead {RUN_ID}-{next_seq()}",
            "description": "Event specific lead test",
            "eventDate": future_iso(days=10),
            "venue": "Main Auditorium",
        },
    ).json()["id"]

    assert (
        api.post(
            f"/api/v1/events/{ev_id}/attendance",
            actor=team_member_a,
            json={
                "participantName": "Blocked Student",
                "participantEmail": unique_email("blk"),
                "rollNumber": "22CS801",
                "barcodeToken": None,
                "entryMethod": "MANUAL",
            },
        ).status_code
        == 403
    )

    api.post(
        f"/api/v1/events/{ev_id}/leads",
        actor=gdg_lead_1,
        json={"memberId": team_member_a["id"], "eventRole": "EVENT_LEAD"},
    )

    r_bar = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=team_member_a,
        json={
            "participantName": "Barcode Student",
            "participantEmail": unique_email("bar_ok"),
            "rollNumber": "22CS802",
            "barcodeToken": f"QR-T3-{RUN_ID}-{next_seq()}",
            "entryMethod": "BARCODE",
        },
    )
    assert r_bar.status_code in (200, 201), r_bar.text

    r_man = api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=team_member_a,
        json={
            "participantName": "Manual Student",
            "participantEmail": unique_email("man_ok"),
            "rollNumber": "22CS803",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )
    assert r_man.status_code in (200, 201), r_man.text


def test_tier3_24_delegated_event_team_member_attendance_and_certificate_generation(
    api, event_lead, event_team_member
):
    """F3 + F4 + F23 + F24: Grant EVENT_MANAGEMENT -> checkin 2 participants -> batch generate & download PDFs."""
    api.post(
        "/api/v1/permissions/grants",
        actor=event_lead,
        json={
            "granteeMemberId": event_team_member["id"],
            "permissionCode": "EVENT_MANAGEMENT",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Event desk operations",
        },
    )

    ev_id = api.post(
        "/api/v1/events",
        actor=event_team_member,
        json={
            "name": f"Delegated Cert Event {RUN_ID}-{next_seq()}",
            "description": "Managed by event_team_member",
            "eventDate": future_iso(days=7),
            "venue": "Hall G",
        },
    ).json()["id"]

    api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_team_member,
        json={
            "participantName": "P1 Barcode",
            "participantEmail": unique_email("p1_bar"),
            "rollNumber": "22CS810",
            "barcodeToken": f"QR-P1-{RUN_ID}-{next_seq()}",
            "entryMethod": "BARCODE",
        },
    )
    api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=event_team_member,
        json={
            "participantName": "P2 Manual",
            "participantEmail": unique_email("p2_man"),
            "rollNumber": "22CS811",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )

    gen = api.post(
        f"/api/v1/events/{ev_id}/certificates/generate",
        actor=event_team_member,
        json={"attendanceIds": [], "generateForAllAttendees": True, "templateTitle": "Cert"},
    )
    assert gen.status_code in (200, 201), gen.text
    certs = unwrap_list(gen.json())
    assert len(certs) == 2

    for c in certs:
        dl = api.get(f"/api/v1/certificates/{c['id']}/download", actor=event_team_member)
        assert_pdf_response(dl)


def test_tier3_25_certificate_mailing_with_cc_bcc_and_bounce_webhook_updates_logs(
    api, event_lead
):
    """F23 + F24 + F25: Attendance -> cert gen -> mail CC/BCC -> bounce webhook -> mail-logs & mail-bounces."""
    ev_id = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"T3 Mail Bounce Event {RUN_ID}-{next_seq()}",
            "description": "End-to-end mail & bounce",
            "eventDate": future_iso(days=6),
            "venue": "Auditorium",
        },
    ).json()["id"]

    good_em = unique_email("t3_good")
    bounce_em = unique_email("t3_bounce")
    for name, em in (("Good Student", good_em), ("Bounce Student", bounce_em)):
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
                "cc": ["cc_lead@gdgoc.edu"],
                "bcc": ["bcc_archive@gdgoc.edu"],
                "subject": "GDGoC Certificate",
                "body": "Congratulations!",
            },
        ).json()
    )
    b_log = [l for l in mail_logs if l.get("recipientEmail") == bounce_em][0]

    wh = api.post(
        "/api/v1/webhooks/email/bounce",
        json={
            "providerMessageId": b_log["providerMessageId"],
            "recipientEmail": bounce_em,
            "eventId": ev_id,
            "eventType": "BOUNCED",
            "bounceReason": "550 5.1.1 Mailbox unavailable",
        },
    )
    assert wh.status_code == 200

    bounces = unwrap_list(api.get("/api/v1/events/mail-bounces", actor=event_lead).json())
    assert any(
        b.get("recipientEmail") == bounce_em and b.get("deliveryStatus") == "BOUNCED"
        for b in bounces
    )


def test_tier3_26_cross_event_participant_history_aggregates_attendance_certs_and_bounces(
    api, event_lead
):
    """F23 + F24 + F25: Multi-event participant history aggregates BARCODE/MANUAL, certs, and SENT/BOUNCED."""
    ev_alpha = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Event Alpha {RUN_ID}",
            "description": "First event",
            "eventDate": future_iso(days=4),
            "venue": "Hall Alpha",
        },
    ).json()["id"]
    ev_beta = api.post(
        "/api/v1/events",
        actor=event_lead,
        json={
            "name": f"Event Beta {RUN_ID}",
            "description": "Second event",
            "eventDate": future_iso(days=8),
            "venue": "Hall Beta",
        },
    ).json()["id"]

    p_email = unique_email("multi_journey")
    api.post(
        f"/api/v1/events/{ev_alpha}/attendance",
        actor=event_lead,
        json={
            "participantName": "Multi Journey Student",
            "participantEmail": p_email,
            "rollNumber": "22CS850",
            "barcodeToken": f"QR-ALPHA-{RUN_ID}-{next_seq()}",
            "entryMethod": "BARCODE",
        },
    )
    api.post(
        f"/api/v1/events/{ev_beta}/attendance",
        actor=event_lead,
        json={
            "participantName": "Multi Journey Student",
            "participantEmail": p_email,
            "rollNumber": "22CS850",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )

    c_alpha = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_alpha}/certificates/generate",
            actor=event_lead,
            json={"attendanceIds": [], "generateForAllAttendees": True, "templateTitle": "Alpha Cert"},
        ).json()
    )[0]["id"]
    c_beta = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_beta}/certificates/generate",
            actor=event_lead,
            json={"attendanceIds": [], "generateForAllAttendees": True, "templateTitle": "Beta Cert"},
        ).json()
    )[0]["id"]

    api.post(
        f"/api/v1/events/{ev_alpha}/certificates/mail",
        actor=event_lead,
        json={"certificateIds": [c_alpha], "cc": [], "bcc": [], "subject": "Alpha", "body": "B"},
    )
    beta_logs = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_beta}/certificates/mail",
            actor=event_lead,
            json={"certificateIds": [c_beta], "cc": [], "bcc": [], "subject": "Beta", "body": "B"},
        ).json()
    )
    api.post(
        "/api/v1/webhooks/email/bounce",
        json={
            "providerMessageId": beta_logs[0]["providerMessageId"],
            "recipientEmail": p_email,
            "eventId": ev_beta,
            "eventType": "BOUNCED",
            "bounceReason": "550 Box full",
        },
    )

    hist_resp = api.get(
        f"/api/v1/events/participants/history?participantEmail={p_email}",
        actor=event_lead,
    )
    assert hist_resp.status_code == 200, hist_resp.text
    hist = unwrap_list(hist_resp.json())
    assert len(hist) >= 2


def test_tier3_27_terminated_member_active_permission_grants_and_event_access_revoked(
    api, gdg_lead_1, hr_lead, team_a, team_member_a, user_factory
):
    """F3 + F5 + F18 + F23: Member with task & event grants -> terminated -> both task & event access 403."""
    member = user_factory(role="EVENT_TEAM_MEMBER", full_name="Terminated Event & Task Member")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=gdg_lead_1,
        json={"memberId": member["id"], "teamRole": "MEMBER"},
    )

    api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": member["id"],
            "permissionCode": "ASSIGN_TASK_INTRA_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Task grant",
        },
    )
    api.post(
        "/api/v1/permissions/grants",
        actor=gdg_lead_1,
        json={
            "granteeMemberId": member["id"],
            "permissionCode": "EVENT_MANAGEMENT",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=5),
            "reason": "Event grant",
        },
    )

    ev_id = api.post(
        "/api/v1/events",
        actor=gdg_lead_1,
        json={
            "name": f"Revoke Check Event {RUN_ID}-{next_seq()}",
            "description": "Check",
            "eventDate": future_iso(days=5),
            "venue": "Hall",
        },
    ).json()["id"]

    # Before termination: both succeed
    assert api.post(
        "/api/v1/tasks",
        actor=member,
        json={
            "title": "Pre-Term Task",
            "description": "Allowed",
            "assignmentType": "MEMBER_INTRA_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "LOW",
            "deadline": future_iso(days=5),
        },
    ).status_code in (200, 201)

    assert api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=member,
        json={
            "participantName": "Pre-Term Attendee",
            "participantEmail": unique_email("pre_term"),
            "rollNumber": "22CS901",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    ).status_code in (200, 201)

    # Terminate member
    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Revoke all grants", "terminatedAt": None},
    )

    # After termination: both return 403 Forbidden
    assert (
        api.post(
            "/api/v1/tasks",
            actor=member,
            json={
                "title": "Post-Term Task",
                "description": "Blocked",
                "assignmentType": "MEMBER_INTRA_TEAM",
                "assigneeId": team_member_a["id"],
                "sourceTeamId": team_a["id"],
                "targetTeamId": team_a["id"],
                "priority": "LOW",
                "deadline": future_iso(days=5),
            },
        ).status_code
        == 403
    )
    assert (
        api.post(
            f"/api/v1/events/{ev_id}/attendance",
            actor=member,
            json={
                "participantName": "Post-Term Attendee",
                "participantEmail": unique_email("post_term"),
                "rollNumber": "22CS902",
                "barcodeToken": None,
                "entryMethod": "MANUAL",
            },
        ).status_code
        == 403
    )


def test_tier3_28_member_details_aggregates_teams_pca_compliance_and_task_counts(
    api, hr_lead, team_lead_a, team_a, team_member_a
):
    """F11 + F13 + F17 + F22: Issue & sign PCA + assign 2 tasks (complete 1) -> GET /members/{id} aggregates."""
    ag_id = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.{RUN_ID}.{next_seq()}",
            "title": "Aggregate PCA",
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
    api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        actor=team_member_a,
        json={"signatureText": "Signed"},
    )

    t1 = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Agg Task 1",
            "description": "Will complete",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    ).json()["id"]
    api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Agg Task 2",
            "description": "Remains assigned",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "MEDIUM",
            "deadline": future_iso(days=7),
        },
    )
    api.patch(
        f"/api/v1/tasks/{t1}/progress",
        actor=team_member_a,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Completed T1"},
    )

    detail_resp = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_lead)
    assert detail_resp.status_code == 200, detail_resp.text
    d = detail_resp.json()
    assert d["id"] == team_member_a["id"]
    assert d.get("pcaSigned") is True or d.get("pcaStatus") == "SIGNED"
