# UI 2.0 LDAP TLS trust store cannot be given a PIN or a format — finding

## Status

**FINDING REPORTED, 2026-09-12. NOT FIXED HERE.** This is the TLS trust
boundary of the operator bind, specified by `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`
§4 (FROZEN). Changing how a trust anchor is loaded is a security-boundary
change on a frozen contract, so this document states the gap and stops.

Evidence grade: **repository source plus measurement against the real library
and JDK.** No real corporate directory was contacted, and no TLS handshake was
performed — §4 below states exactly what that leaves unproven.

## 1. The code

`ui2/ldap-adapter/.../UnboundIdOperatorBindAdapter.java` and
`UnboundIdRevalidationAdapter.java` both build their trust manager as:

```java
SSLUtil sslUtil = new SSLUtil(new TrustStoreTrustManager(caBundlePath.toString()));
```

That constructor takes the file path only. It accepts **no trust-store PIN and
no explicit format**, and the code offers no way to supply either.

## 2. Measured

On this toolchain — JDK 21.0.10, UnboundID LDAP SDK 7.0.1 (the version the
version catalog pins):

| Observation | Result |
| --- | --- |
| `KeyStore.getDefaultType()` | `pkcs12` |
| `KeyStore.load(jks, null)` on a password-protected JKS built by `keytool` | 1 entry — loads |
| `KeyStore.load(p12, null)` on a password-protected PKCS12 built by `keytool` | **0 entries, no exception** |
| `new TrustStoreTrustManager("ts.jks").getAcceptedIssuers()` | 0 |
| `new TrustStoreTrustManager("ts.p12").getAcceptedIssuers()` | 0 |

Two things follow, and they matter separately.

**A password-protected PKCS12 store opened with a null PIN yields zero entries
and raises nothing.** It does not fail loudly; it succeeds emptily. PKCS12 is
also the JDK's default keystore type since JDK 9, and it is the normal shape of
a modern corporate CA bundle.

**The single-argument constructor names no format**, so the format falls to the
JVM default. A JKS bundle and a PKCS12 default therefore disagree, and the
disagreement is not reported at construction time either — the class validates
lazily, which its own comment in the adapter says.

## 3. Why this contradicts the contract

`C3` §4 requires the operator bind to be TLS-verified against the corporate CA
bundle. As coded, the bundle can only be read if it is unprotected **and** in
the JVM's default format. A password-protected bundle — the ordinary case —
cannot be read at all, and nothing in the code path reports that it was not
read.

`AGENTS.md` "Production TLS requires trusted corporate CA verification" for
Palo Alto, and the Check Point section requires trusted host keys; the same
principle is what `C3` §4 states for the directory. A trust store that silently
loads empty is the failure mode those rules exist to prevent, because the
product believes it is verifying against an anchor set it does not have.

## 4. What is NOT proven here

- **No handshake was performed.** `getAcceptedIssuers()` returning zero is
  consistent with lazy loading, so it is not by itself proof that
  `checkServerTrusted` would reject a valid chain. The solid, handshake-
  independent finding is the one in §2: there is no way to supply a PIN or a
  format, and a password-protected PKCS12 opened with a null PIN is empty.
- Whether this estate's CA bundle is JKS, PKCS12, password-protected, or a PEM
  file is `UNKNOWN` here. If it is an unprotected store in the default format,
  the current code works and this is latent rather than live.
- Whether the same gap affects any Line-1 TLS path is out of scope and was not
  examined.

## 5. What a fix needs, in order

1. Establish the estate's actual CA bundle format and whether it carries a PIN.
   That is an environment fact, not a code decision, and it determines whether
   this is live or latent.
2. A `C3` successor or amendment deciding how the PIN and format are supplied —
   a secret file per `C1` §6's `<COMPONENT>_<PURPOSE>_FILE` pattern is the
   repository's existing shape for exactly this, and a trust-store PIN is a
   secret.
3. Only then the code change, with a test that **fails when the trust store
   loads zero anchors** — the silent-empty case is the one this finding is
   about, so a fix whose test only proves the happy path has not closed it.

Doing 3 without 1 and 2 would pick a format and a PIN source by guess, on a
security boundary, against a frozen contract.

## 6. Related, and distinct

One directory test (`C3` §8 test 15, revalidation while the directory is
unreachable) is left `@Disabled` because of this: `UnboundIdOperatorBindAdapter`
carries a package-private plain-socket seam used by the four enabled directory
tests, and `UnboundIdRevalidationAdapter` has no equivalent, so the
revalidation path cannot be exercised without a trust store that loads. That is
a test-reach consequence of the gap, not a second defect.
