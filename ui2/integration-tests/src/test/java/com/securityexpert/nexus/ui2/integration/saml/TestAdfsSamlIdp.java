package com.securityexpert.nexus.ui2.integration.saml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.zip.Inflater;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.Marshaller;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.encryption.Encrypter;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.CredentialSupport;
import org.opensaml.xmlsec.encryption.support.DataEncryptionParameters;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;
import org.opensaml.xmlsec.encryption.support.KeyEncryptionParameters;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * A stand-in for ADFS, in this process, for tests only. It holds a key pair and a self-signed certificate generated at run time
 * (nothing secret-shaped is committed), builds the SAML 2.0 Response an ADFS would POST to the assertion consumer service, and
 * signs it with the JDK's own XML signature code (so the service's OpenSAML verification is checked by an independent
 * implementation). Every field a real ADFS could get wrong or an attacker could change is a parameter of {@link Options}.
 */
public final class TestAdfsSamlIdp {
    public static final String DEFAULT_GROUP_CLAIM = "http://schemas.xmlsoap.org/claims/Group";
    private static final String NS_SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String NS_SAMLP = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String NS_DS = "http://www.w3.org/2000/09/xmldsig#";

    public final String entityId = "https://adfs.test.example/adfs/services/trust";
    public final String ssoUrl = "https://adfs.test.example/adfs/ls/";
    private final KeyPair keys;
    private final X509Certificate certificate;

    public TestAdfsSamlIdp() {
        this.keys = generateKeys();
        this.certificate = selfSigned(keys, "CN=Test ADFS Token Signing");
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public String certificatePem() {
        return pem(certificate);
    }

    /** The metadata an administrator would paste: the EntityDescriptor with its IDPSSODescriptor, as ADFS publishes it. */
    public String metadataXml() {
        String cert = Base64.getEncoder().encodeToString(encoded(certificate));
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<EntityDescriptor xmlns=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\"" + entityId + "\">"
                + "<IDPSSODescriptor protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">"
                + "<KeyDescriptor use=\"signing\"><KeyInfo xmlns=\"" + NS_DS + "\"><X509Data><X509Certificate>" + cert
                + "</X509Certificate></X509Data></KeyInfo></KeyDescriptor>"
                + "<SingleSignOnService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" Location=\"" + ssoUrl + "\"/>"
                + "<SingleSignOnService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\" Location=\"" + ssoUrl + "\"/>"
                + "</IDPSSODescriptor></EntityDescriptor>";
    }

    /** What the service redirected the browser to: the AuthnRequest ID inside its SAMLRequest (HTTP-Redirect, deflated). */
    public static String authnRequestId(String redirectLocation) throws Exception {
        String xml = authnRequestXml(redirectLocation);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\sID=\"([^\"]+)\"").matcher(xml);
        if (!m.find()) throw new IllegalStateException("no ID in the AuthnRequest");
        return m.group(1);
    }

    public static String authnRequestXml(String redirectLocation) throws Exception {
        String query = java.net.URI.create(redirectLocation).getRawQuery();
        for (String pair : query.split("&")) {
            if (pair.startsWith("SAMLRequest=")) {
                byte[] deflated = Base64.getMimeDecoder().decode(java.net.URLDecoder.decode(pair.substring(12), StandardCharsets.UTF_8));
                Inflater inflater = new Inflater(true);
                inflater.setInput(deflated);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (!inflater.finished()) {
                    int n = inflater.inflate(buffer);
                    if (n == 0 && inflater.needsInput()) break;
                    out.write(buffer, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("no SAMLRequest in the redirect");
    }

    /** One scenario's parameters; the defaults are a valid ADFS answer to {@code inResponseTo}. */
    public static final class Options {
        public String inResponseTo;
        public String acs;
        public String audience;
        public String issuer;
        public String nameId = "alice@corp.test";
        public String nameIdFormat = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";
        public String groupClaim = DEFAULT_GROUP_CLAIM;
        public List<String> groups = new ArrayList<>();
        public String displayName = "Alice Example";
        public String assertionId = "_" + UUID.randomUUID();
        public Instant issueInstant = Instant.now();
        public Instant notBefore = Instant.now().minusSeconds(5);
        public Instant notOnOrAfter = Instant.now().plusSeconds(300);
        public Instant subjectNotOnOrAfter = Instant.now().plusSeconds(300);
        public Instant sessionNotOnOrAfter = Instant.now().plusSeconds(3600);
        public boolean signAssertion = true;
        public boolean signResponse = false;
        /** Sign with this key instead of the IdP's own (a rogue IdP). */
        public KeyPair signWith;
        public X509Certificate signWithCertificate;
        /** Change the NameID after signing, so the signature no longer matches the content. */
        public String tamperNameIdAfterSigning;
        /** Encrypt the (signed) assertion to this certificate, as ADFS does when the relying party trust asks for it. */
        public X509Certificate encryptTo;
        public boolean omitConditionsExpiry;

        public Options answering(String requestId, String acsUrl, String spEntityId) {
            this.inResponseTo = requestId;
            this.acs = acsUrl;
            this.audience = spEntityId;
            return this;
        }

        public Options inGroups(String... values) {
            this.groups = new ArrayList<>(List.of(values));
            return this;
        }
    }

    public Options options() {
        Options o = new Options();
        o.issuer = entityId;
        return o;
    }

    /** The base64 SAMLResponse form field value. */
    public String response(Options o) throws Exception {
        return Base64.getEncoder().encodeToString(responseXml(o).getBytes(StandardCharsets.UTF_8));
    }

    public String responseXml(Options o) throws Exception {
        Document doc = parse(unsigned(o));
        Element response = doc.getDocumentElement();
        Element assertion = (Element) doc.getElementsByTagNameNS(NS_SAML, "Assertion").item(0);
        response.setIdAttribute("ID", true);
        assertion.setIdAttribute("ID", true);
        KeyPair key = o.signWith != null ? o.signWith : keys;
        X509Certificate cert = o.signWithCertificate != null ? o.signWithCertificate : certificate;
        if (o.signAssertion) sign(assertion, key, cert);
        if (o.tamperNameIdAfterSigning != null) {
            ((Element) doc.getElementsByTagNameNS(NS_SAML, "NameID").item(0)).setTextContent(o.tamperNameIdAfterSigning);
        }
        // Order matters: sign the assertion, then encrypt it, then sign the response that now holds the encrypted assertion.
        if (o.encryptTo != null) encrypt(doc, o.encryptTo);
        if (o.signResponse) sign(response, key, cert);
        return serialize(doc);
    }

    private static String unsigned(Options o) {
        String groups = o.groups.isEmpty() ? "" : "<Attribute Name=\"" + o.groupClaim + "\">"
                + o.groups.stream().map(g -> "<AttributeValue>" + esc(g) + "</AttributeValue>").reduce("", String::concat)
                + "</Attribute>";
        String name = o.displayName == null ? "" : "<Attribute Name=\"http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name\">"
                + "<AttributeValue>" + esc(o.displayName) + "</AttributeValue></Attribute>";
        String attributes = groups.isEmpty() && name.isEmpty() ? "" : "<AttributeStatement>" + name + groups + "</AttributeStatement>";
        return "<samlp:Response xmlns:samlp=\"" + NS_SAMLP + "\" xmlns=\"" + NS_SAML + "\" ID=\"_" + UUID.randomUUID()
                + "\" Version=\"2.0\" IssueInstant=\"" + o.issueInstant + "\" Destination=\"" + o.acs + "\""
                + (o.inResponseTo == null ? "" : " InResponseTo=\"" + o.inResponseTo + "\"") + ">"
                + "<Issuer>" + o.issuer + "</Issuer>"
                + "<samlp:Status><samlp:StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/></samlp:Status>"
                + "<Assertion ID=\"" + o.assertionId + "\" IssueInstant=\"" + o.issueInstant + "\" Version=\"2.0\">"
                + "<Issuer>" + o.issuer + "</Issuer>"
                + "<Subject><NameID Format=\"" + o.nameIdFormat + "\">" + esc(o.nameId) + "</NameID>"
                + "<SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\"><SubjectConfirmationData"
                + (o.inResponseTo == null ? "" : " InResponseTo=\"" + o.inResponseTo + "\"")
                + " NotOnOrAfter=\"" + o.subjectNotOnOrAfter + "\" Recipient=\"" + o.acs + "\"/></SubjectConfirmation></Subject>"
                + "<Conditions NotBefore=\"" + o.notBefore + "\"" + (o.omitConditionsExpiry ? "" : " NotOnOrAfter=\"" + o.notOnOrAfter + "\"")
                + "><AudienceRestriction><Audience>" + o.audience + "</Audience></AudienceRestriction></Conditions>"
                + attributes
                + "<AuthnStatement AuthnInstant=\"" + o.issueInstant + "\" SessionIndex=\"_s" + UUID.randomUUID() + "\""
                + (o.sessionNotOnOrAfter == null ? "" : " SessionNotOnOrAfter=\"" + o.sessionNotOnOrAfter + "\"") + ">"
                + "<AuthnContext><AuthnContextClassRef>urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport"
                + "</AuthnContextClassRef></AuthnContext></AuthnStatement></Assertion></samlp:Response>";
    }

    // ---- XML signature (enveloped, exclusive c14n, RSA-SHA256), inserted after the Issuer ----

    private static void sign(Element target, KeyPair key, X509Certificate cert) throws Exception {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = factory.newReference("#" + target.getAttribute("ID"),
                factory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        factory.newTransform("http://www.w3.org/2001/10/xml-exc-c14n#", (TransformParameterSpec) null)), null, null);
        SignedInfo info = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null), Collections.singletonList(reference));
        KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(Collections.singletonList(
                keyInfoFactory.newX509Data(Collections.singletonList(cert))));
        Node issuer = target.getElementsByTagNameNS(NS_SAML, "Issuer").item(0);
        // The Issuer that is a direct child of the signed element; its next sibling is where the Signature goes.
        for (Node child = target.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e && "Issuer".equals(e.getLocalName())) { issuer = child; break; }
        }
        DOMSignContext context = new DOMSignContext(key.getPrivate(), target, issuer.getNextSibling());
        context.setDefaultNamespacePrefix("ds");
        factory.newXMLSignature(info, keyInfo).sign(context);
    }

    // ---- encryption (OpenSAML), applied to the already signed assertion ----

    private static void encrypt(Document doc, X509Certificate to) throws Exception {
        Element assertionElement = (Element) doc.getElementsByTagNameNS(NS_SAML, "Assertion").item(0);
        Assertion assertion = (Assertion) XMLObjectSupport.unmarshallFromInputStream(
                XMLObjectProviderRegistrySupport.getParserPool(), new ByteArrayInputStream(
                        serialize(assertionElement).getBytes(StandardCharsets.UTF_8)));
        Credential recipient = CredentialSupport.getSimpleCredential(to, null);
        DataEncryptionParameters data = new DataEncryptionParameters();
        data.setAlgorithm(EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128);
        KeyEncryptionParameters keyParams = new KeyEncryptionParameters();
        keyParams.setAlgorithm(EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP);
        keyParams.setEncryptionCredential(recipient);
        EncryptedAssertion encrypted = new Encrypter(data, keyParams).encrypt(assertion);
        Marshaller marshaller = XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(encrypted);
        Element encryptedElement = (Element) doc.importNode(marshaller.marshall(encrypted), true);
        assertionElement.getParentNode().replaceChild(encryptedElement, assertionElement);
    }

    // ---- helpers ----

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String serialize(Node node) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(out));
        return out.toString();
    }

    private static String esc(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** A fresh RSA key pair, for the IdP itself and for the "wrong key" and SP-key scenarios. */
    public static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A self-signed certificate for {@code keys}, valid from a minute ago for a day. */
    public static X509Certificate selfSigned(KeyPair keys, String subject) {
        try {
            long now = System.currentTimeMillis();
            X500Name name = new X500Name(subject);
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), new Date(now - 60_000),
                    new Date(now + 86_400_000L), name, keys.getPublic());
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(X509Certificate certificate) {
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded(certificate)) + "\n-----END CERTIFICATE-----\n";
    }

    /** PKCS#8 PEM of a private key. The label is assembled so no key-shaped literal sits in the source. */
    public static String privateKeyPem(KeyPair keys) {
        String label = "PRIVATE " + "KEY";
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keys.getPrivate().getEncoded()) + "\n-----END " + label + "-----\n";
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Referenced so the test classpath proves the provider the service uses is the one on it. */
    static Class<?> serviceProvider() {
        return OpenSaml5AuthenticationProvider.class;
    }
}
