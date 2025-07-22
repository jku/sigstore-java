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
package dev.sigstore.encryption.signers;

import dev.sigstore.AlgorithmRegistry;
import java.security.*;

/** ECDSA signer, use {@link Signers} to instantiate}. */
public class EcdsaSigner implements Signer {

  private final KeyPair keyPair;
  private final AlgorithmRegistry.HashAlgorithm hashAlgorithm;

  EcdsaSigner(KeyPair keyPair, AlgorithmRegistry.HashAlgorithm hashAlgorithm) {
    this.keyPair = keyPair;
    this.hashAlgorithm = hashAlgorithm;
  }

  @Override
  public PublicKey getPublicKey() {
    return keyPair.getPublic();
  }

  @Override
  public byte[] sign(byte[] artifact)
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
    Signature signature = Signature.getInstance(hashAlgorithm + "withECDSA");
    signature.initSign(keyPair.getPrivate());
    signature.update(artifact);
    return signature.sign();
  }

  @Override
  public byte[] signDigest(byte[] artifactDigest)
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
    if (artifactDigest.length != hashAlgorithm.getLength()) {
      throw new SignatureException(
          "Artifact digest must be " + hashAlgorithm.getLength() + " bytes");
    }
    Signature signature = Signature.getInstance("NONEwithECDSA");
    signature.initSign(keyPair.getPrivate());
    signature.update(artifactDigest);
    return signature.sign();
  }
}
