/*
 * Copyright (c) 2014 Wael Chatila / Icegreen Technologies. All Rights Reserved.
 * This software is released under the Apache license 2.0
 * This file has been used and modified.
 * Original file can be found on http://foedus.sourceforge.net
 */
package com.icegreen.greenmail.smtp;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.mail.MailAddress;
import com.icegreen.greenmail.mail.MovingMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.UserManager;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;

public class SmtpManager {
    protected static final Logger log = LoggerFactory.getLogger(SmtpManager.class);

    Incoming _incomingQueue;

    UserManager userManager;

    private final ImapHostManager imapHostManager;

    private static final String MONGO_DB_DATABASE = "OUTILLAGE";

    private static final String MONGO_DB_COLLECTION = "utilisateurs_greenmail";

    private static final String MONGO_DB_IP = "cdmongotools01t.bbo1t.local:27017";

    List<WaitObject> notifyList;

    public SmtpManager(final ImapHostManager imapHostManager, final UserManager userManager) {
        this.imapHostManager = imapHostManager;
        this.userManager = userManager;
        _incomingQueue = new Incoming();
        notifyList = Collections.synchronizedList(new ArrayList<WaitObject>());
    }

    public String checkSender(final SmtpState state, final MailAddress sender) {
        //always ok
        return null;
    }

    public String checkRecipient(final SmtpState state, final MailAddress rcpt) {
        // todo?
//        MailAddress sender = state.getMessage().getReturnPath();
        return null;
    }

    public String checkData(final SmtpState state) {
        return null;
    }

    public synchronized void send(final SmtpState state) {
        _incomingQueue.enqueue(state.getMessage());
        for (WaitObject o : notifyList) {
            synchronized (o) {
                o.emailReceived();
            }
        }
    }

    /**
     * @return null if no need to wait. Otherwise caller must call wait() on the returned object
     */
    public synchronized WaitObject createAndAddNewWaitObject(final int emailCount) {
        final int existingCount = imapHostManager.getAllMessages().size();
        if (existingCount >= emailCount) {
            return null;
        }
        WaitObject ret = new WaitObject(emailCount - existingCount);
        notifyList.add(ret);
        return ret;
    }

    //~----------------------------------------------------------------------------------------------------------------

    /**
     * This Object is used by a thread to wait until a number of emails have arrived.
     * (for example Server's waitForIncomingEmail method)
     * <p/>
     * Every time an email has arrived, the method emailReceived() must be called.
     * <p/>
     * The notify() or notifyALL() methods should not be called on this object unless
     * you want to notify waiting threads even if not all the required emails have arrived.
     */
    public static class WaitObject {
        private boolean arrived = false;

        private int emailCount;

        public WaitObject(final int emailCount) {
            this.emailCount = emailCount;
        }

        public int getEmailCount() {
            return emailCount;
        }

        public boolean isArrived() {
            return arrived;
        }

        private void setArrived() {
            arrived = true;
        }

        public void emailReceived() {
            emailCount--;
            if (emailCount <= 0) {
                setArrived();
                synchronized (this) {
                    notifyAll();
                }
            }
        }
    }

    private class Incoming {
        public void enqueue(final MovingMessage msg) {
            for (MailAddress address : msg.getToAddresses()) {
                handle(msg, address);
            }

        }

        private void handle(final MovingMessage msg, final MailAddress mailAddress) {
            try {
                GreenMailUser user = userManager.getUserByEmail(mailAddress.getEmail());
                if (null == user) {
                    String login = mailAddress.getEmail();
                    String email = mailAddress.getEmail();
                    String password = mailAddress.getEmail();
                    user = userManager.createUser(email, login, password);
                    log.info("Created user login {} for address {} with password {} because it didn't exist before.", login, email, password);
                    saveUserMail(email);
                    log.info("Saved user login {} in BDD", email);
                }

                user.deliver(msg);
            } catch (Exception e) {
                log.error("Can not deliver message " + msg + " to " + mailAddress, e);
                throw new RuntimeException(e);
            }

            msg.releaseContent();
        }

        /** Sauvegarde du mail de l'utilisateur pour autocomplétion dashboard */
        private void saveUserMail(final String pMail) {
            MongoClient client;
            try {
                client = new MongoClient(MONGO_DB_IP);
                DB db = client.getDB(MONGO_DB_DATABASE);
                DBCollection collection = db.getCollection(MONGO_DB_COLLECTION);
                BasicDBObject userDb = new BasicDBObject();
                userDb.put("mail", pMail);
                //on update avec upsert à true pour créer si l'entrée n'existe pas. Ca évite les doublons.
                collection.update(userDb, userDb, true, false);
            } catch (UnknownHostException e) {
                throw new RuntimeException("Erreur lors du stockage de l'adresse mail de l'utilisateur : BDD injoignable ", e);
            }
        }
    }
}
