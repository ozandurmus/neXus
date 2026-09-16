package com.securityexpert.nexus.ui2.persistence.identity;

/** Repositories bound to the same locked mutation/audit transaction. */
public record DirectoryMutationRepositories(RoleBindingRepository bindings, ActorAuthzStateRepository actors,
        SessionRepository sessions, LocalCredentialsRepository locals) { }
