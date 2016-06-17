package com.uber.jenkins.phabricator.utils;

import com.appscode.core.dao.ConduitTokenDao;
import com.appscode.core.dao.UserDao;
import com.appscode.core.jdo.Configs;
import com.appscode.core.jdo.PhabricatorTransactorFactoryHolder;
import com.appscode.core.jdo.SystemTransactorFactoryHolder;
import com.appscode.core.jdo.Transactor;
import com.appscode.core.model.ConduitToken;
import com.appscode.core.model.User;
import com.uber.jenkins.phabricator.conduit.ConduitAPIClient;
import com.uber.jenkins.phabricator.conduit.ConduitAPIException;
import hudson.model.Job;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;

import com.appscode.core.system.Environment;
import org.datanucleus.api.jdo.JDOPersistenceManager;

public class PhUtil {
    private static String rc = "abcdefghijklmnopqrstuvwxyz234567";

    private static String genToken() {
        byte[] bytes = new byte[28];
        Random rand = new Random();
        rand.nextBytes(bytes);
        String out = "";
        for (int i = 0; i < 28; i++) {
            out += rc.charAt(bytes[i] >>> 3);
        }
        return out;
    }

    public static String getNamespace(Job owner) {
        try {
            String url = URLDecoder.decode(owner.getUrl(), "UTF_8");
            if (url.startsWith("job/")) {
                return url.split("/")[1];
            }
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        return null;
    }

    public static String getBaseDomain() {
        String env = Environment.get();
        if ("prod".equals(env)) {
            return "appscode.io";
        } else if ("qa".equals(env)) {
            return "appscode.ninja";
        } else {
            return "appscode.dev";
        }
    }

    public static String getPhabricatorURL(Job owner) {
        String baseUri = getNamespace(owner) + '.' + getBaseDomain();
        if (Environment.get().equals("prod") || Environment.get().equals("qa")) {
            return "https://" + baseUri + '/';
        } else {
            return "http://" + baseUri + '/';
        }
    }

    public static String getConduitToken(Job owner, Logger logger) throws Exception {
        String namespace = getNamespace(owner);
        final User jenkinsBot = loadJenkinsBot(namespace);

        Transactor tx = PhabricatorTransactorFactoryHolder.get().get(namespace);
        final ConduitTokenDao tokenDao = new ConduitTokenDao();
        List<ConduitToken> tokens = tx.retrieve(new Function<JDOPersistenceManager, List<ConduitToken>>() {
            @Override
            public List<ConduitToken> apply(JDOPersistenceManager pm) {
                return tokenDao.list(pm, jenkinsBot.getPhid());
            }
        });
        if (tokens.isEmpty()) {
            final ConduitToken token = new ConduitToken();
            token.setObjectPHID(jenkinsBot.getPhid());
            token.setTokenType("api");
            token.setToken(genToken());
            tx.run(new Consumer<JDOPersistenceManager>() {
                @Override
                public void accept(JDOPersistenceManager pm) {
                    pm.makePersistent(token);
                }
            });
            return token.getToken();
        }
        return tokens.get(0).getToken();
    }

    private static User loadJenkinsBot(String namespace) throws Exception {
        Transactor systx = SystemTransactorFactoryHolder.get().get();
        final String jenkinsBot = Configs.value(systx, "ci.default-bot");

        Transactor tx = PhabricatorTransactorFactoryHolder.get().get(namespace);
        return tx.retrieve(new Function<JDOPersistenceManager, User>() {
            @Override
            public com.appscode.core.model.User apply(JDOPersistenceManager pm) {
                return new UserDao().getByUserName(pm, jenkinsBot);
            }
        });
    }

    public static ConduitAPIClient getConduitClient(Job owner, Logger logger) throws ConduitAPIException {
        try {
            String token = getConduitToken(owner, logger);
            if (token == null) {
                throw new ConduitAPIException("No credentials configured for conduit");
            }
            return new ConduitAPIClient(getPhabricatorURL(owner), token);
        } catch (Exception e) {
            throw new ConduitAPIException("No credentials configured for conduit");
        }
    }
}
