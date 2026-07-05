package com.eop.assistant.model;

import java.util.Set;

/**
 * The Rung-1 tool allow-list — the explicit safety assertion that Rung 1 ships <b>exactly</b> these four
 * uniform "accept-into-field" advisory tools (architect ruling Q4):
 *
 * <ul>
 *   <li>{@code draftDescription} — draft an application description</li>
 *   <li>{@code draftJustification} — draft an access-request justification</li>
 *   <li>{@code recommendScopes} — suggest scopes from the known catalog</li>
 *   <li>{@code validateRedirectUris} — advise on redirect-URI shape</li>
 * </ul>
 *
 * <p>All four are symmetric: the model proposes a value the user accepts into a form field — no server write, no
 * retrieval, no answer/oracle shape. The fifth tool in the frozen response enum, {@code checkGroupOwnership}, is
 * deliberately <b>excluded</b>: it is answer-shaped (needs a distinct render mode, not accept-into-field) and
 * carries an "not-yours vs doesn't-exist" info-leak oracle, so it moves to Rung 2 where its responses are
 * normalized. Five is the tool universe; Rung 1 ships these four.
 *
 * <p>This constant is the allow-list the deterministic {@link ToolProposalValidator} enforces. The port PR wires
 * nothing to it yet (the controller stays 501); it is the contract the Rung-1 build will validate against.
 */
public final class Rung1Tools {

    public static final String DRAFT_DESCRIPTION = "draftDescription";
    public static final String DRAFT_JUSTIFICATION = "draftJustification";
    public static final String RECOMMEND_SCOPES = "recommendScopes";
    public static final String VALIDATE_REDIRECT_URIS = "validateRedirectUris";

    /** The frozen Rung-1 allow-list. Anything a model proposes outside this set is rejected in Java. */
    public static final Set<String> ALLOW_LIST = Set.of(
            DRAFT_DESCRIPTION, DRAFT_JUSTIFICATION, RECOMMEND_SCOPES, VALIDATE_REDIRECT_URIS);

    private Rung1Tools() {
    }
}
