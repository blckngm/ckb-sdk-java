package org.nervos.ckb.sign.signer;

import org.nervos.ckb.crypto.Blake2b;
import org.nervos.ckb.crypto.Hash;
import org.nervos.ckb.crypto.secp256k1.ECKeyPair;
import org.nervos.ckb.crypto.secp256k1.Sign;
import org.nervos.ckb.sign.Context;
import org.nervos.ckb.sign.ScriptGroup;
import org.nervos.ckb.sign.ScriptSigner;
import org.nervos.ckb.type.Script;
import org.nervos.ckb.type.fixed.UInt64;
import org.nervos.ckb.type.transaction.Transaction;
import org.nervos.ckb.utils.Numeric;

import java.util.Arrays;
import java.util.List;

public class Secp256k1Blake160SighashAllSigner implements ScriptSigner {
  private static final int WITNESS_OFFSET_IN_BYTE = 20;
  private static final int SIGNATURE_LENGTH_IN_BYTE = 65;

  private static Secp256k1Blake160SighashAllSigner INSTANCE;

  private Secp256k1Blake160SighashAllSigner() {
  }

  public static Secp256k1Blake160SighashAllSigner getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new Secp256k1Blake160SighashAllSigner();
    }
    return INSTANCE;
  }

  @Override
  public boolean signTransaction(
      Transaction transaction, ScriptGroup scriptGroup, Context context) {
    Script script = scriptGroup.getScript();
    String privateKey = context.getPrivateKey();
    if (isMatched(privateKey, script.args)) {
      return signScriptGroup(transaction, scriptGroup, privateKey);
    } else {
      return false;
    }
  }

  public boolean signScriptGroup(
      Transaction transaction, ScriptGroup scriptGroup, String privateKey) {
    ECKeyPair ecKeyPair = ECKeyPair.createWithPrivateKey(privateKey, false);

    byte[] txHash = transaction.computeHash();
    List<byte[]> witnesses = transaction.witnesses;
    Blake2b blake2b = new Blake2b();
    blake2b.update(txHash);

    for (int i : scriptGroup.getInputIndices()) {
      byte[] witness = witnesses.get(i);
      blake2b.update(new UInt64(witness.length).toBytes());
      blake2b.update(witness);
    }
    for (int i = transaction.inputs.size(); i < transaction.witnesses.size(); i++) {
      byte[] witness = witnesses.get(i);
      blake2b.update(new UInt64(witness.length).toBytes());
      blake2b.update(witness);
    }

    byte[] message = blake2b.doFinalBytes();
    byte[] signature = Sign.signMessage(message, ecKeyPair).getSignature();

    int index = scriptGroup.getInputIndices().get(0);
    // TODO: need parsing from witnessArgs but not replace in place
    byte[] witness = witnesses.get(index);
    byte[] finalWitness = new byte[witnesses.get(index).length + SIGNATURE_LENGTH_IN_BYTE];
    int pos = 0;
    System.arraycopy(witness, 0, finalWitness, 0, WITNESS_OFFSET_IN_BYTE);
    pos += WITNESS_OFFSET_IN_BYTE;
    System.arraycopy(signature, 0, finalWitness, pos, SIGNATURE_LENGTH_IN_BYTE);
    pos += SIGNATURE_LENGTH_IN_BYTE;
    System.arraycopy(
        witness,
        WITNESS_OFFSET_IN_BYTE,
        finalWitness,
        pos,
        witness.length - WITNESS_OFFSET_IN_BYTE);

    witnesses.set(index, finalWitness);
    return true;
  }

  // Check if the script with `scriptArgs` is generated by and can be unlocked by `privateKey`
  public boolean isMatched(String privateKey, byte[] scriptArgs) {
    if (scriptArgs == null || privateKey == null) {
      return false;
    }
    String scriptArgsHex = Numeric.toHexString(scriptArgs);
    ECKeyPair ecKeyPair = ECKeyPair.createWithPrivateKey(privateKey, true);
    byte[] publicKeyHash = Hash.blake160(ecKeyPair.getPublicKeyBytes());
    byte[] scriptArgsBytes = Numeric.hexStringToByteArray(scriptArgsHex);
    return Arrays.equals(scriptArgsBytes, publicKeyHash);
  }
}