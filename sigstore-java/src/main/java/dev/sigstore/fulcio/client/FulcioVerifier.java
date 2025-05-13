/*
 * Copyright 2022 The Sigstore Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.sigstore.fulcio.client;

import com.google.common.annotations.VisibleForTesting;
import dev.sigstore.encryption.certificates.Certificates;
import dev.sigstore.encryption.certificates.transparency.CTLogInfo;
import dev.sigstore.encryption.certificates.transparency.CTVerificationResult;
import dev.sigstore.encryption.certificates.transparency.CTVerifier;
import dev.sigstore.trustroot.CertificateAuthority;
import dev.sigstore.trustroot.SigstoreTrustedRoot;
import dev.sigstore.trustroot.TransparencyLog;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Verifier for fulcio generated signing cerificates */
public class FulcioVerifier {
  private final List<CertificateAuthority> cas;
  private final List<TransparencyLog> ctLogs;
  private final CTVerifier ctVerifier;

  public static FulcioVerifier newFulcioVerifier(SigstoreTrustedRoot trustRoot)
      throws InvalidAlgorithmParameterException,
          CertificateException,
          InvalidKeySpecException,
          NoSuchAlgorithmException {
    return newFulcioVerifier(trustRoot.getCAs(), trustRoot.getCTLogs());
  }

  public static FulcioVerifier newFulcioVerifier(
      List<CertificateAuthority> cas, List<TransparencyLog> ctLogs)
      throws InvalidKeySpecException,
          NoSuchAlgorithmException,
          InvalidAlgorithmParameterException,
          CertificateException {
    List<CTLogInfo> logs = new ArrayList<>();
    for (var ctLog : ctLogs) {
      logs.add(
          new CTLogInfo(
              ctLog.getPublicKey().toJavaPublicKey(), "CT Log", ctLog.getBaseUrl().toString()));
    }
    var verifier =
        new CTVerifier(
            logId ->
                logs.stream()
                    .filter(ctLogInfo -> Arrays.equals(ctLogInfo.getID(), logId))
                    .findFirst()
                    .orElse(null));

    // check to see if we can use all fulcio roots (this is a bit eager)
    for (var ca : cas) {
      ca.asTrustAnchor();
    }

    return new FulcioVerifier(cas, ctLogs, verifier);
  }

  private FulcioVerifier(
      List<CertificateAuthority> cas, List<TransparencyLog> ctLogs, CTVerifier ctVerifier) {
    this.cas = cas;
    this.ctLogs = ctLogs;
    this.ctVerifier = ctVerifier;
  }

  @VisibleForTesting
  void verifySct(CertPath fullCertPath) throws FulcioVerificationException {
    if (ctLogs.size() == 0) {
      throw new FulcioVerificationException("No ct logs were provided to verifier");
    }

    if (Certificates.getEmbeddedSCTs(Certificates.getLeaf(fullCertPath)).isPresent()) {
      verifyEmbeddedScts(fullCertPath);
    } else {
      throw new FulcioVerificationException("No valid SCTs were found during verification");
    }
  }

  private void verifyEmbeddedScts(CertPath certPath) throws FulcioVerificationException {
    @SuppressWarnings("unchecked")
    var certs = (List<X509Certificate>) certPath.getCertificates();
    CTVerificationResult result;
    try {
      result = ctVerifier.verifySignedCertificateTimestamps(certs, null, null);
    } catch (CertificateEncodingException cee) {
      throw new FulcioVerificationException(
          "Certificates could not be parsed during SCT verification");
    }

    // these are technically valid, but we have the additional constraint of sigstore's trustroot
    // providing a validity period for logs, so make sure all SCTs were signed by a log during
    // that log's validity period
    for (var validSct : result.getValidSCTs()) {
      var sct = validSct.sct;

      var logId = sct.getLogID();
      var entryTime = Instant.ofEpochMilli(sct.getTimestamp());

      var ctLog = TransparencyLog.find(ctLogs, logId, entryTime);
      if (ctLog.isPresent()) {
        // TODO: currently we only require one valid SCT, but maybe this should be configurable?
        // found at least one valid sct with a matching valid log
        return;
      }
    }
    throw new FulcioVerificationException(
        "No valid SCTs were found, all("
            + (result.getValidSCTs().size() + result.getInvalidSCTs().size())
            + ") SCTs were invalid");
  }

  /**
   * Verify that a cert chain is valid and chains up to the trust anchor (fulcio public key)
   * configured in this validator. Also verify that the leaf certificate contains at least one valid
   * SCT
   *
   * @param signingCertificate containing a certificate chain, this chain should not contain any
   *     trusted root or trusted intermediates
   * @throws FulcioVerificationException if verification fails for any reason
   */
  public void verifySigningCertificate(CertPath signingCertificate)
      throws FulcioVerificationException, IOException {
    CertPath fullCertPath = validateCertPath(signingCertificate);
    verifySct(fullCertPath);
  }

  public CertPath trimTrustedParent(CertPath signingCertificate)
      throws FulcioVerificationException, CertificateException {
    for (var ca : cas) {
      if (Certificates.containsParent(signingCertificate, ca.getCertPath())) {
        return Certificates.trimParent(signingCertificate, ca.getCertPath());
      }
    }
    throw new FulcioVerificationException("Certificate does not chain to trusted roots");
  }

  /**
   * Find a valid cert path that chains back up to the trusted root certs and reconstruct a
   * certificate path combining the provided un-trusted certs and a known set of trusted and
   * intermediate certs. If a full certificate is provided with a self signed root, this should
   * attempt to match the root/intermediate with a trusted chain.
   */
  CertPath validateCertPath(CertPath signingCertificate) throws FulcioVerificationException {
    CertPathValidator cpv;
    try {
      cpv = CertPathValidator.getInstance("PKIX");
    } catch (NoSuchAlgorithmException e) {
      //
      throw new RuntimeException(
          "No PKIX CertPathValidator, we probably shouldn't be here, but this seems to be a system library error not a program control flow issue",
          e);
    }

    var leaf = Certificates.getLeaf(signingCertificate);
    var validCAs = CertificateAuthority.find(cas, leaf.getNotBefore().toInstant());

    if (validCAs.size() == 0) {
      throw new FulcioVerificationException(
          "No valid Certificate Authorities found when validating certificate");
    }

    Map<String, String> caVerificationFailure = new LinkedHashMap<>();

    for (var ca : validCAs) {
      PKIXParameters pkixParams;
      try {
        pkixParams = new PKIXParameters(Collections.singleton(ca.asTrustAnchor()));
      } catch (InvalidAlgorithmParameterException | CertificateException e) {
        throw new RuntimeException(
            "Can't create PKIX parameters for fulcioRoot. This should have been checked when generating a verifier instance",
            e);
      }
      pkixParams.setRevocationEnabled(false);

      // these certs are only valid for 15 minutes, so find a time in the validity period
      @SuppressWarnings("JavaUtilDate")
      Date dateInValidityPeriod =
          new Date(Certificates.getLeaf(signingCertificate).getNotBefore().getTime());
      pkixParams.setDate(dateInValidityPeriod);

      CertPath fullCertPath;
      try {
        if (Certificates.isSelfSigned(signingCertificate)) {
          if (Certificates.containsParent(signingCertificate, ca.getCertPath())) {
            fullCertPath = signingCertificate;
          } else {
            // verification failed because we didn't match to a trusted root
            caVerificationFailure.put(
                ca.getUri().toString(), "Trusted root in chain does not match");
            continue;
          }
        } else {
          // build a cert chain with the root-chain in question and the provided signing certificate
          fullCertPath = Certificates.append(ca.getCertPath(), signingCertificate);
        }

        // a result is returned here, but we ignore it
        cpv.validate(fullCertPath, pkixParams);
      } catch (CertPathValidatorException
          | InvalidAlgorithmParameterException
          | CertificateException ve) {
        caVerificationFailure.put(ca.getUri().toString(), ve.getMessage());
        // verification failed
        continue;
      }
      return fullCertPath;
      // verification passed so just end this method
    }
    String errors =
        caVerificationFailure.entrySet().stream()
            .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
            .collect(Collectors.joining("\n"));
    throw new FulcioVerificationException("Certificate was not verifiable against CAs\n" + errors);
  }
}
