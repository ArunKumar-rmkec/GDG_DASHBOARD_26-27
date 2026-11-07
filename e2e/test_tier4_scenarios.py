"""
Tier 4 — Real-World Multi-Actor Chapter Lifecycle Scenarios (15 Tests: test_scenario_01..15).

Each scenario orchestrates 6–12 sequential HTTP operations and cross-module state assertions
across multiple chapter personas (`gdg_lead_1..3`, `hr_lead`, `team_lead_a..b`, `event_lead`,
`hr_member`, `event_team_member`, `team_member_a..b`).
"""

import time
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


def test_scenario_01_new_academic_year_team_bootstrapping_via_3_gdg_leads(
    api, gdg_lead_1, gdg_lead_2, gdg_lead_3, hr_lead, team_lead_a, user_factory
):
    """F1, F2, F6, F8, F10: 3-GDG-Lead team & sub-team creation, duplicate vote 409, rename, and stats."""
    parent_name = f"Cloud-DevOps-{RUN_ID}-{next_seq()}"
    r1 = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={
            "actionType": "CREATE_TEAM",
            "proposedName": parent_name,
            "proposedDescription": "Cloud & DevOps Track",
        },
    )
    assert r1.status_code in (200, 201), r1.text
    req_id = r1.json()["id"]
    assert r1.json()["status"] == "PENDING"

    # Step 2: Duplicate vote by gdg_lead_1 rejected with 409
    dup = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_1,
        json={"approved": True, "comment": "Duplicate vote"},
    )
    assert dup.status_code == 409, dup.text

    # Step 3: Vote 2/3 keeps request PENDING
    r2 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_2,
        json={"approved": True, "comment": "2/3"},
    )
    assert r2.json()["status"] == "PENDING"

    # Step 4: Vote 3/3 executes creation
    r3 = api.post(
        f"/api/v1/teams/consensus-requests/{req_id}/vote",
        actor=gdg_lead_3,
        json={"approved": True, "comment": "3/3"},
    )
    assert r3.json()["status"] == "EXECUTED"
    team_id = r3.json()["executedTeamId"]

    # Step 5: Create sub-team under parent team via 3-Lead consensus
    sub_team = create_approved_team(
        api,
        (gdg_lead_1, gdg_lead_2, gdg_lead_3),
        name=f"Kubernetes-SIG-{RUN_ID}-{next_seq()}",
        parent_team_id=team_id,
    )
    assert sub_team["parentTeamId"] == team_id

    # Step 6: Assign Team Lead and 2 members
    m1 = user_factory(role="TEAM_MEMBER", full_name="Cloud Member 1")
    m2 = user_factory(role="TEAM_MEMBER", full_name="Cloud Member 2")
    api.post(
        f"/api/v1/teams/{team_id}/members",
        actor=hr_lead,
        json={"memberId": team_lead_a["id"], "teamRole": "TEAM_LEAD"},
    )
    for m in (m1, m2):
        api.post(
            f"/api/v1/teams/{team_id}/members",
            actor=hr_lead,
            json={"memberId": m["id"], "teamRole": "MEMBER"},
        )

    # Step 7: Rename team via 3-Lead consensus
    renamed = f"GCP-Platform-Team-{RUN_ID}-{next_seq()}"
    ren_id = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={
            "actionType": "RENAME_TEAM",
            "targetTeamId": team_id,
            "proposedName": renamed,
            "proposedDescription": "Google Cloud Platform Team",
        },
    ).json()["id"]
    api.post(
        f"/api/v1/teams/consensus-requests/{ren_id}/vote",
        actor=gdg_lead_2,
        json={"approved": True, "comment": "2/3"},
    )
    api.post(
        f"/api/v1/teams/consensus-requests/{ren_id}/vote",
        actor=gdg_lead_3,
        json={"approved": True, "comment": "3/3"},
    )

    # Step 8: Verify stats
    stats = api.get(f"/api/v1/teams/{team_id}/stats", actor=gdg_lead_1).json()
    assert stats["teamName"] == renamed
    assert stats["activeMemberCount"] == 3
    assert stats["subTeamCount"] == 1


def test_scenario_02_member_onboarding_pca_compliance_and_appointment_letter_lifecycle(
    api, hr_lead, team_lead_a, team_a
):
    """F1, F7, F11, F13, F20: HR onboarding -> login -> team assign -> PCA sign -> Appointment letter -> PDFs."""
    email = unique_email("s02_onboard")
    password = "Password123!"
    create_resp = api.post(
        "/api/v1/members",
        actor=hr_lead,
        json={
            "email": email,
            "password": password,
            "fullName": "Onboarded Chapter Member",
            "rollNumber": unique_roll(),
            "department": "CSE",
            "academicYear": 2,
            "phone": "+919876500202",
            "roles": ["TEAM_MEMBER"],
        },
    )
    assert create_resp.status_code in (200, 201), create_resp.text
    member_id = create_resp.json().get("id") or create_resp.json().get("member", {}).get("id")

    login_resp = api.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert login_resp.status_code == 200, login_resp.text
    member_token = login_resp.json()["accessToken"]

    assert api.get("/api/v1/profile/me", token=member_token).status_code == 200

    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=hr_lead,
        json={"memberId": member_id, "teamRole": "MEMBER"},
    )

    ag_id = api.post(
        "/api/v1/agreements",
        actor=hr_lead,
        json={
            "version": f"2026.S02.{RUN_ID}.{next_seq()}",
            "title": "Onboarding PCA 2026",
            "contentHtml": "<h1>Contributor Agreement</h1><p>Onboarding terms.</p>",
        },
    ).json()["id"]
    iss = api.post(
        f"/api/v1/agreements/{ag_id}/issue",
        actor=hr_lead,
        json={"memberIds": [member_id]},
    ).json()
    sigs = unwrap_list(iss)
    sig_id = sigs[0]["id"] if sigs else iss["id"]

    api.post(
        f"/api/v1/agreements/signatures/{sig_id}/sign",
        token=member_token,
        json={"signatureText": "Signed by Onboarded Chapter Member"},
    )

    appt_id = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": member_id,
            "letterType": "APPOINTMENT",
            "title": "Chapter Appointment 2026",
            "contentHtml": "<h1>Appointment Letter</h1><p>Welcome aboard!</p>",
        },
    ).json()["id"]

    letters = unwrap_list(api.get("/api/v1/profile/me/letters", token=member_token).json())
    assert len(letters) >= 2
    for l in letters:
        dl = api.get(f"/api/v1/profile/me/letters/{l['id']}/download", token=member_token)
        assert_pdf_response(dl)

    detail = api.get(f"/api/v1/members/{member_id}", actor=team_lead_a).json()
    assert detail.get("pcaSigned") is True or detail.get("pcaStatus") == "SIGNED"
    assert appt_id is not None


def test_scenario_03_hr_team_delegated_bio_data_audit_with_dual_lead_authorization(
    api, hr_lead, team_lead_a, hr_member, team_a, team_member_a
):
    """F3, F4, F7, F11, F21: Self-service bio request + HR Member delegated audit with dual lead approval."""
    req1 = api.post(
        "/api/v1/profile/me/bio-data-requests",
        actor=team_member_a,
        json={
            "fullName": "Phase 1 Self Updated",
            "phone": "+919876500301",
            "department": "CSE",
            "academicYear": 3,
            "skills": "Python, TensorFlow",
            "reason": "Self-service skill update",
        },
    ).json()
    req1_id = req1.get("id") or req1.get("changeRequestId")

    r_part = api.post(
        f"/api/v1/change-requests/{req1_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead 1/2"},
    )
    assert r_part.json()["status"] == "PARTIALLY_APPROVED"
    assert api.get("/api/v1/profile/me", actor=team_member_a).json()["fullName"] != "Phase 1 Self Updated"

    r_exec = api.post(
        f"/api/v1/change-requests/{req1_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead 2/2"},
    )
    assert r_exec.json()["status"] == "EXECUTED"
    assert api.get("/api/v1/profile/me", actor=team_member_a).json()["fullName"] == "Phase 1 Self Updated"

    # Delegated HR_MEMBER bio-data audit
    api.post(
        "/api/v1/permissions/grants",
        actor=hr_lead,
        json={
            "granteeMemberId": hr_member["id"],
            "permissionCode": "BIO_DATA_EDIT_HR_TEAM",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=3),
            "reason": "HR audit",
        },
    )
    staged = api.put(
        f"/api/v1/members/{team_member_a['id']}/bio-data",
        actor=hr_member,
        json={
            "fullName": "Phase 2 HR Audited Name",
            "department": "AI&DS",
            "academicYear": 4,
            "phone": "+919876500302",
        },
    ).json()
    req2_id = staged.get("changeRequestId") or staged.get("id")

    api.post(
        f"/api/v1/change-requests/{req2_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead first"},
    )
    api.post(
        f"/api/v1/change-requests/{req2_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead second"},
    )

    final_detail = api.get(f"/api/v1/members/{team_member_a['id']}", actor=hr_member).json()
    assert final_detail["fullName"] == "Phase 2 HR Audited Name"
    assert final_detail["department"] == "AI&DS"


def test_scenario_04_member_inter_team_transfer_and_past_member_audit_trail(
    api, hr_lead, team_lead_a, team_lead_b, team_a, team_b, user_factory
):
    """F7, F8, F9, F20, F22: Blank reason 400 -> valid transfer -> dual approval -> past members & stats."""
    mover = user_factory(role="TEAM_MEMBER", full_name="Inter Team Mover")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=hr_lead,
        json={"memberId": mover["id"], "teamRole": "MEMBER"},
    )

    # Blank reason rejected with 400
    bad_resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=mover,
        json={"currentTeamId": team_a["id"], "targetTeamId": team_b["id"], "reason": "   "},
    )
    assert bad_resp.status_code == 400, bad_resp.text

    b_stats_before = api.get(f"/api/v1/teams/{team_b['id']}/stats", actor=team_lead_b).json()

    valid_resp = api.post(
        "/api/v1/profile/me/team-change-requests",
        actor=mover,
        json={
            "currentTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "reason": "Transitioning to Mobile Flutter track",
        },
    )
    assert valid_resp.status_code in (200, 201, 202), valid_resp.text
    req_id = valid_resp.json().get("id") or valid_resp.json().get("changeRequestId")

    api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=hr_lead,
        json={"approved": True, "comment": "HR Lead approves transfer"},
    )
    exec_resp = api.post(
        f"/api/v1/change-requests/{req_id}/approve",
        actor=team_lead_a,
        json={"approved": True, "comment": "Team Lead A approves transfer"},
    )
    assert exec_resp.json()["status"] == "EXECUTED"

    past_a = unwrap_list(
        api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=team_lead_a).json()
    )
    assert any(p.get("memberId") == mover["id"] and p.get("leftAt") is not None for p in past_a)

    b_stats_after = api.get(f"/api/v1/teams/{team_b['id']}/stats", actor=team_lead_b).json()
    assert b_stats_after["activeMemberCount"] >= b_stats_before["activeMemberCount"] + 1

    prof = api.get("/api/v1/profile/me", actor=mover).json()
    assert team_b["id"] in prof.get("teamIds", [])


def test_scenario_05_hierarchical_task_cascade_from_gdg_lead_to_team_lead_to_members(
    api, gdg_lead_1, team_lead_a, team_member_a, team_a
):
    """F8, F14, F15, F16, F17: GDG_LEAD -> TEAM_LEAD milestone -> TEAM_MEMBER sub-task -> cascade completion."""
    parent_task = api.post(
        "/api/v1/tasks",
        actor=gdg_lead_1,
        json={
            "title": "Deliver Q3 Cloud Native Platform",
            "description": "Chapter milestone task",
            "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
            "assigneeId": team_lead_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "CRITICAL",
            "deadline": future_iso(days=14),
        },
    ).json()
    parent_id = parent_task["id"]

    api.patch(
        f"/api/v1/tasks/{parent_id}/progress",
        actor=team_lead_a,
        json={"progressPercentage": 25, "status": "IN_PROGRESS", "note": "Decomposed into team tasks"},
    )

    sub_task = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Build CI/CD Pipeline",
            "description": "Sub-task for Alpha member",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=7),
        },
    ).json()
    sub_id = sub_task["id"]

    api.patch(
        f"/api/v1/tasks/{sub_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": 50, "status": "IN_PROGRESS", "note": "GitHub Actions configured"},
    )
    api.patch(
        f"/api/v1/tasks/{sub_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Pipeline green"},
    )

    sub_track = api.get(f"/api/v1/tasks/{sub_id}/tracking", actor=team_lead_a)
    assert sub_track.status_code == 200

    api.patch(
        f"/api/v1/tasks/{parent_id}/progress",
        actor=team_lead_a,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "All sub-tasks delivered"},
    )

    parent_track = api.get(f"/api/v1/tasks/{parent_id}/tracking", actor=gdg_lead_1)
    assert parent_track.status_code == 200

    stats = api.get(f"/api/v1/teams/{team_a['id']}/stats", actor=gdg_lead_1).json()
    assert stats["completedTasks"] >= 2
    assert stats["taskCompletionRate"] > 0


def test_scenario_06_cross_team_collaboration_and_cross_team_task_approval_pipeline(
    api, hr_member, team_lead_a, team_lead_b, team_member_b, team_a, team_b
):
    """F10, F12, F14, F15, F17, F19: Collab creation -> dual team lead approval -> cross-team task -> completion."""
    collab_id = api.post(
        "/api/v1/collaborations",
        actor=hr_member,
        json={
            "title": "AI + Web Integration Sprint",
            "description": "Cross-team sprint",
            "collaborationType": "CROSS_TEAM",
            "leadTeamId": team_a["id"],
            "participatingTeamIds": [team_a["id"], team_b["id"]],
            "deadline": future_iso(days=20),
        },
    ).json()["id"]

    api.post(
        f"/api/v1/collaborations/{collab_id}/approve-team",
        actor=team_lead_a,
        json={"teamId": team_a["id"], "approved": True, "comment": "Team A ready"},
    )
    api.post(
        f"/api/v1/collaborations/{collab_id}/approve-team",
        actor=team_lead_b,
        json={"teamId": team_b["id"], "approved": True, "comment": "Team B ready"},
    )

    task_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Integrate Gemini Endpoint",
            "description": "Cross-team task",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "collaborationId": collab_id,
            "priority": "HIGH",
            "deadline": future_iso(days=10),
        },
    )
    task_id = task_resp.json()["id"]
    assert task_resp.json()["status"] == "PENDING_TEAM_LEAD_APPROVAL"

    # Early progress blocked
    assert api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 10, "status": "IN_PROGRESS", "note": "Blocked"},
    ).status_code in (400, 409)

    # Target Team Lead approves
    api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": True, "comment": "Approved for sprint"},
    )

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 40, "status": "IN_PROGRESS", "note": "SDK wired"},
    )
    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Shipped"},
    )

    assert api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_lead_a).status_code == 200
    assert api.get(f"/api/v1/collaborations/{collab_id}", actor=hr_member).status_code == 200


def test_scenario_07_temporary_delegated_member_cross_team_task_assignment_and_expiry(
    api, team_lead_a, team_lead_b, team_member_a, team_member_b, team_a, team_b
):
    """F3, F4, F15, F18, F19: 403 -> grant ASSIGN_TASK_CROSS_TEAM -> assign & approve & complete -> revoke -> 403."""
    assert (
        api.post(
            "/api/v1/tasks",
            actor=team_member_a,
            json={
                "title": "Blocked Before Grant",
                "description": "No grant",
                "assignmentType": "MEMBER_CROSS_TEAM",
                "assigneeId": team_member_b["id"],
                "sourceTeamId": team_a["id"],
                "targetTeamId": team_b["id"],
                "priority": "MEDIUM",
                "deadline": future_iso(days=5),
            },
        ).status_code
        == 403
    )

    grant_id = api.post(
        "/api/v1/permissions/grants",
        actor=team_lead_a,
        json={
            "granteeMemberId": team_member_a["id"],
            "permissionCode": "ASSIGN_TASK_CROSS_TEAM",
            "scopeTeamId": team_a["id"],
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(hours=2),
            "reason": "Temporary cross-team task coordinator",
        },
    ).json()["id"]

    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_member_a,
        json={
            "title": "Delegated Cross-Team Task",
            "description": "Created under temporary grant",
            "assignmentType": "MEMBER_CROSS_TEAM",
            "assigneeId": team_member_b["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_b["id"],
            "priority": "HIGH",
            "deadline": future_iso(days=5),
        },
    )
    assert t_resp.status_code in (200, 201), t_resp.text
    task_id = t_resp.json()["id"]

    api.post(
        f"/api/v1/tasks/{task_id}/approve-cross-team",
        actor=team_lead_b,
        json={"approved": True, "comment": "Approved"},
    )
    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_b,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Finished"},
    )
    assert api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_member_a).status_code == 200

    api.delete(f"/api/v1/permissions/grants/{grant_id}", actor=team_lead_a)

    assert (
        api.post(
            "/api/v1/tasks",
            actor=team_member_a,
            json={
                "title": "Blocked After Revocation",
                "description": "Grant revoked",
                "assignmentType": "MEMBER_CROSS_TEAM",
                "assigneeId": team_member_b["id"],
                "sourceTeamId": team_a["id"],
                "targetTeamId": team_b["id"],
                "priority": "MEDIUM",
                "deadline": future_iso(days=5),
            },
        ).status_code
        == 403
    )


def test_scenario_08_flagship_devfest_event_end_to_end_attendance_certificates_mailing_and_bounces(
    api, gdg_lead_1, event_lead, team_member_a
):
    """F23, F24, F25: Event creation -> event-specific lead -> barcode/manual checkins -> 409 dup -> certs -> mail -> bounces."""
    ev_id = api.post(
        "/api/v1/events",
        actor=gdg_lead_1,
        json={
            "name": f"DevFest On Campus 2026 {RUN_ID}",
            "description": "Flagship annual conference",
            "eventDate": future_iso(days=14),
            "venue": "Main Auditorium",
        },
    ).json()["id"]

    api.post(
        f"/api/v1/events/{ev_id}/leads",
        actor=event_lead,
        json={"memberId": team_member_a["id"], "eventRole": "EVENT_LEAD"},
    )

    qr1 = f"QR-DEVFEST-1-{RUN_ID}"
    qr2 = f"QR-DEVFEST-2-{RUN_ID}"
    em1 = unique_email("devfest_p1")
    em2 = unique_email("devfest_p2")
    em3 = unique_email("devfest_p3_bounce")

    api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=team_member_a,
        json={
            "participantName": "Participant One",
            "participantEmail": em1,
            "rollNumber": "22CS911",
            "barcodeToken": qr1,
            "entryMethod": "BARCODE",
        },
    )
    api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=team_member_a,
        json={
            "participantName": "Participant Two",
            "participantEmail": em2,
            "rollNumber": "22CS912",
            "barcodeToken": qr2,
            "entryMethod": "BARCODE",
        },
    )
    api.post(
        f"/api/v1/events/{ev_id}/attendance",
        actor=team_member_a,
        json={
            "participantName": "Participant Three",
            "participantEmail": em3,
            "rollNumber": "22CS913",
            "barcodeToken": None,
            "entryMethod": "MANUAL",
        },
    )

    # Duplicate barcode rejected with 409
    assert (
        api.post(
            f"/api/v1/events/{ev_id}/attendance",
            actor=team_member_a,
            json={
                "participantName": "Dup Barcode",
                "participantEmail": unique_email("dup"),
                "rollNumber": "22CS999",
                "barcodeToken": qr1,
                "entryMethod": "BARCODE",
            },
        ).status_code
        == 409
    )

    certs = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_id}/certificates/generate",
            actor=event_lead,
            json={
                "attendanceIds": [],
                "generateForAllAttendees": True,
                "templateTitle": "DevFest 2026 Certificate",
            },
        ).json()
    )
    assert len(certs) == 3
    for c in certs:
        assert_pdf_response(api.get(f"/api/v1/certificates/{c['id']}/download", actor=event_lead))

    mail_logs = unwrap_list(
        api.post(
            f"/api/v1/events/{ev_id}/certificates/mail",
            actor=event_lead,
            json={
                "certificateIds": [c["id"] for c in certs],
                "cc": ["advisor@rmkec.ac.in"],
                "bcc": ["archive@gdgoc.edu"],
                "subject": "Your DevFest 2026 Certificate",
                "body": "Thank you for attending DevFest!",
            },
        ).json()
    )
    assert len(mail_logs) == 3

    log1 = [l for l in mail_logs if l.get("recipientEmail") == em1][0]
    log3 = [l for l in mail_logs if l.get("recipientEmail") == em3][0]

    api.post(
        "/api/v1/webhooks/email/bounce",
        json={
            "providerMessageId": log1["providerMessageId"],
            "recipientEmail": em1,
            "eventId": ev_id,
            "eventType": "DELIVERED",
            "bounceReason": None,
        },
    )
    api.post(
        "/api/v1/webhooks/email/bounce",
        json={
            "providerMessageId": log3["providerMessageId"],
            "recipientEmail": em3,
            "eventId": ev_id,
            "eventType": "BOUNCED",
            "bounceReason": "550 5.1.1 User unknown",
        },
    )

    assert api.get(f"/api/v1/events/{ev_id}/mail-logs", actor=event_lead).status_code == 200
    bounces = unwrap_list(api.get("/api/v1/events/mail-bounces", actor=event_lead).json())
    assert any(b.get("recipientEmail") == em3 for b in bounces)


def test_scenario_09_member_termination_immediate_lockout_and_10_day_letter_window_lifecycle(
    api, hr_lead, team_lead_a, team_a, user_factory
):
    """F3, F5, F9, F20: Active grants + letters -> terminate -> immediate 403 lockout -> 10-day letter PDF access."""
    member = user_factory(role="TEAM_MEMBER", full_name="Scenario 09 Terminated")
    api.post(
        f"/api/v1/teams/{team_a['id']}/members",
        actor=hr_lead,
        json={"memberId": member["id"], "teamRole": "MEMBER"},
    )
    for ltype in ("APPOINTMENT", "RECOMMENDATION"):
        api.post(
            "/api/v1/letters/issue",
            actor=hr_lead,
            json={
                "memberId": member["id"],
                "letterType": ltype,
                "title": f"{ltype} Letter",
                "contentHtml": f"<h1>{ltype}</h1>",
            },
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
    assert api.get("/api/v1/teams/stats", actor=member).status_code == 200

    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Disciplinary termination", "terminatedAt": None},
    )

    # Operational endpoints immediately locked out
    assert api.get("/api/v1/teams/stats", actor=member).status_code == 403
    assert api.get("/api/v1/teams", actor=member).status_code == 403
    assert (
        api.post(
            "/api/v1/profile/me/team-change-requests",
            actor=member,
            json={"currentTeamId": team_a["id"], "targetTeamId": team_a["id"], "reason": "No"},
        ).status_code
        == 403
    )

    # Profile and letters remain accessible during 10-day window
    assert api.get("/api/v1/profile/me", actor=member).status_code == 200
    letters = unwrap_list(api.get("/api/v1/profile/me/letters", actor=member).json())
    assert len(letters) >= 3
    for l in letters:
        assert_pdf_response(
            api.get(f"/api/v1/profile/me/letters/{l['id']}/download", actor=member)
        )
        s_url = api.get(f"/api/v1/profile/me/letters/{l['id']}/signed-url", actor=member).json()
        token = s_url.get("token")
        path = s_url.get("signedUrl") or f"/api/v1/letters/signed-download/{token}"
        assert_pdf_response(api.get(path))

    past = unwrap_list(
        api.get(f"/api/v1/teams/{team_a['id']}/past-members", actor=team_lead_a).json()
    )
    assert any(p.get("memberId") == member["id"] and p.get("exitReason") == "TERMINATED" for p in past)


def test_scenario_10_expired_10_day_post_termination_window_blocks_all_letter_downloads(
    api, hr_lead, user_factory
):
    """F5, F20: Terminated 11 days ago -> 403/410 on letter list, direct PDF download, and pre-signed URL."""
    member = user_factory(role="TEAM_MEMBER", full_name="Scenario 10 Expired Window")
    letter_id = api.post(
        "/api/v1/letters/issue",
        actor=hr_lead,
        json={
            "memberId": member["id"],
            "letterType": "APPOINTMENT",
            "title": "Appointment Letter",
            "contentHtml": "<h1>Appointment</h1>",
        },
    ).json()["id"]

    s_resp = api.get(f"/api/v1/profile/me/letters/{letter_id}/signed-url", actor=member)
    token = s_resp.json().get("token") if s_resp.status_code == 200 else "expired-token"

    api.post(
        f"/api/v1/members/{member['id']}/terminate",
        actor=hr_lead,
        json={"reason": "Terminated 11 days ago", "terminatedAt": past_iso(days=11)},
    )

    assert api.get("/api/v1/profile/me/letters", actor=member).status_code in (403, 410)
    assert api.get(f"/api/v1/profile/me/letters/{letter_id}/download", actor=member).status_code in (
        403,
        410,
    )
    assert api.get(f"/api/v1/letters/signed-download/{token}").status_code in (403, 410)


def test_scenario_11_multi_event_participant_journey_across_workshops_and_hackathons(
    api, event_lead, event_team_member
):
    """F3, F4, F23, F24, F25: Delegated EVENT_TEAM_MEMBER runs 3 events -> cross-event history verification."""
    api.post(
        "/api/v1/permissions/grants",
        actor=event_lead,
        json={
            "granteeMemberId": event_team_member["id"],
            "permissionCode": "EVENT_MANAGEMENT",
            "validFrom": past_iso(hours=1),
            "validUntil": future_iso(days=7),
            "reason": "Full season event ops",
        },
    )

    priya_email = unique_email("priya_campus")
    event_ids = []
    for idx, title in enumerate(
        ("GenAI Study Jam", "Flutter Bootcamp", "Winter Hackathon"), start=1
    ):
        ev_id = api.post(
            "/api/v1/events",
            actor=event_lead,
            json={
                "name": f"{title} {RUN_ID}",
                "description": title,
                "eventDate": future_iso(days=idx * 5),
                "venue": f"Hall {idx}",
            },
        ).json()["id"]
        event_ids.append(ev_id)

        method = "BARCODE" if idx in (1, 2) else "MANUAL"
        barcode = f"QR-S11-{idx}-{RUN_ID}" if method == "BARCODE" else None
        api.post(
            f"/api/v1/events/{ev_id}/attendance",
            actor=event_team_member,
            json={
                "participantName": "Priya Student",
                "participantEmail": priya_email,
                "rollNumber": "22CS777",
                "barcodeToken": barcode,
                "entryMethod": method,
            },
        )
        certs = unwrap_list(
            api.post(
                f"/api/v1/events/{ev_id}/certificates/generate",
                actor=event_team_member,
                json={"attendanceIds": [], "generateForAllAttendees": True, "templateTitle": title},
            ).json()
        )
        logs = unwrap_list(
            api.post(
                f"/api/v1/events/{ev_id}/certificates/mail",
                actor=event_team_member,
                json={
                    "certificateIds": [certs[0]["id"]],
                    "cc": ["events@gdgoc.edu"],
                    "bcc": ["archive@gdgoc.edu"],
                    "subject": f"Certificate: {title}",
                    "body": "Congrats!",
                },
            ).json()
        )
        if idx == 3:
            api.post(
                "/api/v1/webhooks/email/bounce",
                json={
                    "providerMessageId": logs[0]["providerMessageId"],
                    "recipientEmail": priya_email,
                    "eventId": ev_id,
                    "eventType": "BOUNCED",
                    "bounceReason": "552 Mailbox full",
                },
            )

    hist = unwrap_list(
        api.get(
            f"/api/v1/events/participants/history?participantEmail={priya_email}",
            actor=event_lead,
        ).json()
    )
    assert len(hist) == 3


def test_scenario_12_consensus_rejection_and_reproposal_for_team_restructuring(
    api, gdg_lead_1, gdg_lead_2, gdg_lead_3, hr_lead, user_factory
):
    """F2, F6, F8, F9: DELETE_TEAM rejected by 2nd lead (team survives) -> reproposed & approved by 3 leads."""
    m1 = user_factory(role="TEAM_MEMBER", full_name="Legacy Member 1")
    m2 = user_factory(role="TEAM_MEMBER", full_name="Legacy Member 2")
    legacy_team = create_approved_team(
        api,
        (gdg_lead_1, gdg_lead_2, gdg_lead_3),
        name=f"Legacy-Web-Team-{RUN_ID}-{next_seq()}",
        members=[m1, m2],
    )
    team_id = legacy_team["id"]

    # First DELETE_TEAM proposal rejected by gdg_lead_2
    req1_id = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={"actionType": "DELETE_TEAM", "targetTeamId": team_id},
    ).json()["id"]
    rej = api.post(
        f"/api/v1/teams/consensus-requests/{req1_id}/vote",
        actor=gdg_lead_2,
        json={"approved": False, "comment": "Wait until semester ends"},
    )
    assert rej.json()["status"] == "REJECTED"

    # Team and its 2 active members remain intact
    stats_mid = api.get(f"/api/v1/teams/{team_id}/stats", actor=hr_lead).json()
    assert stats_mid["activeMemberCount"] == 2

    # Second DELETE_TEAM proposal unanimously approved by all 3 GDG Leads
    req2_id = api.post(
        "/api/v1/teams/consensus-requests",
        actor=gdg_lead_1,
        json={"actionType": "DELETE_TEAM", "targetTeamId": team_id},
    ).json()["id"]
    api.post(
        f"/api/v1/teams/consensus-requests/{req2_id}/vote",
        actor=gdg_lead_2,
        json={"approved": True, "comment": "2/3"},
    )
    exec_del = api.post(
        f"/api/v1/teams/consensus-requests/{req2_id}/vote",
        actor=gdg_lead_3,
        json={"approved": True, "comment": "3/3"},
    )
    assert exec_del.json()["status"] == "EXECUTED"

    past = unwrap_list(api.get(f"/api/v1/teams/{team_id}/past-members", actor=hr_lead).json())
    assert len(past) >= 2
    assert all(p.get("exitReason") == "TEAM_DELETED" for p in past)


def test_scenario_13_strict_non_shareable_permission_and_clearance_boundary_enforcement(
    api, gdg_lead_1, team_lead_a, hr_lead, team_member_a, team_a
):
    """F2, F3, F4, F6, F13, F16: Sweep of all 5 non-shareable permissions and strict role boundaries."""
    for code in (
        "SUPER_ADMIN",
        "GRANT_PERMISSIONS",
        "TEAM_LIFECYCLE_CONSENSUS",
        "PROJECT_CONTRIBUTOR_AGREEMENT",
        "ASSIGN_TASK_TO_TEAM_LEAD",
    ):
        r = api.post(
            "/api/v1/permissions/grants",
            actor=gdg_lead_1,
            json={
                "granteeMemberId": team_lead_a["id"],
                "permissionCode": code,
                "validFrom": past_iso(hours=1),
                "validUntil": future_iso(days=3),
                "reason": f"Verify {code} non-shareability",
            },
        )
        assert r.status_code in (400, 403), f"{code} must not be shareable"

    assert (
        api.post(
            "/api/v1/teams/consensus-requests",
            actor=team_lead_a,
            json={"actionType": "CREATE_TEAM", "proposedName": "Illegal"},
        ).status_code
        == 403
    )
    assert (
        api.post(
            "/api/v1/agreements",
            actor=team_lead_a,
            json={"version": "99.9", "title": "Illegal", "contentHtml": "<p>No</p>"},
        ).status_code
        == 403
    )
    assert (
        api.post(
            "/api/v1/tasks",
            actor=team_lead_a,
            json={
                "title": "Illegal Lead Assignment",
                "description": "No",
                "assignmentType": "GDG_LEAD_TO_TEAM_LEAD",
                "assigneeId": team_lead_a["id"],
                "sourceTeamId": team_a["id"],
                "targetTeamId": team_a["id"],
                "priority": "HIGH",
                "deadline": future_iso(days=5),
            },
        ).status_code
        == 403
    )
    assert (
        api.post(
            "/api/v1/permissions/grants",
            actor=team_member_a,
            json={
                "granteeMemberId": team_member_a["id"],
                "permissionCode": "VIEW_TEAM_STATS",
                "validFrom": past_iso(hours=1),
                "validUntil": future_iso(days=2),
                "reason": "No",
            },
        ).status_code
        == 403
    )


def test_scenario_14_overdue_task_detection_and_assignor_escalation_workflow(
    api, team_lead_a, team_member_a, team_a
):
    """F8, F14, F15, F17: Past deadline rejected 400 -> short-horizon task -> overdue detection -> completion."""
    assert (
        api.post(
            "/api/v1/tasks",
            actor=team_lead_a,
            json={
                "title": "Past Deadline Task",
                "description": "Must fail",
                "assignmentType": "TEAM_LEAD_TO_TEAM",
                "assigneeId": team_member_a["id"],
                "sourceTeamId": team_a["id"],
                "targetTeamId": team_a["id"],
                "priority": "HIGH",
                "deadline": past_iso(days=1),
            },
        ).status_code
        == 400
    )

    t_resp = api.post(
        "/api/v1/tasks",
        actor=team_lead_a,
        json={
            "title": "Short Horizon Escalation Task",
            "description": "Becomes overdue in 2 seconds",
            "assignmentType": "TEAM_LEAD_TO_TEAM",
            "assigneeId": team_member_a["id"],
            "sourceTeamId": team_a["id"],
            "targetTeamId": team_a["id"],
            "priority": "CRITICAL",
            "deadline": future_iso(days=0, hours=0, minutes=0, seconds=2),
        },
    )
    assert t_resp.status_code in (200, 201), t_resp.text
    task_id = t_resp.json()["id"]

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": 30, "status": "IN_PROGRESS", "note": "Started"},
    )

    time.sleep(2.5)

    tr_overdue = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_lead_a).json()
    assert tr_overdue.get("overdue") is True

    overdue_list = unwrap_list(api.get("/api/v1/tasks?overdueOnly=true", actor=team_lead_a).json())
    assert any(t["id"] == task_id for t in overdue_list)

    stats_overdue = api.get(f"/api/v1/teams/{team_a['id']}/stats", actor=team_lead_a).json()
    assert stats_overdue["overdueTasks"] >= 1

    api.patch(
        f"/api/v1/tasks/{task_id}/progress",
        actor=team_member_a,
        json={"progressPercentage": 100, "status": "COMPLETED", "note": "Resolved overdue task"},
    )

    tr_resolved = api.get(f"/api/v1/tasks/{task_id}/tracking", actor=team_lead_a).json()
    assert tr_resolved.get("overdue") is False


def test_scenario_15_chapter_wide_executive_audit_and_observability_check(
    api, gdg_lead_1, team_a, team_b
):
    """F1, F2, F8, F9, F10, F11, F23, F25: GDG Lead executive audit across observability & domain endpoints."""
    assert api.get("/actuator/health").status_code == 200
    assert api.get("/v3/api-docs").status_code == 200

    for endpoint in (
        "/api/v1/teams",
        "/api/v1/teams/stats",
        "/api/v1/teams/past-members",
        "/api/v1/collaborations",
        "/api/v1/members",
        "/api/v1/tasks",
        "/api/v1/events",
        "/api/v1/events/participants/history",
        "/api/v1/events/mail-bounces",
    ):
        resp = api.get(endpoint, actor=gdg_lead_1)
        assert resp.status_code == 200, f"Executive audit failed on {endpoint}: {resp.status_code}"
