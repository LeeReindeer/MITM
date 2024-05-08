package leer.moe.mitm.cert;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;

public record HttpProxyCertConfig(
        String serverIssuer,
        Date caNotBefore,
        Date caNotAfter,
        PrivateKey caPriKey,
        PrivateKey serverPriKey,
        PublicKey serverPubKey
) {
}
