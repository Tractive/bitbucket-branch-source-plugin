/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.bitbucket.hooks;

import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMNavigator;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.BitbucketTagSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.BranchSCMHead;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBranch;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketHref;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketPushEvent;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketPushEvent.Reference;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketPushEvent.Target;
import com.cloudbees.jenkins.plugins.bitbucket.client.BitbucketCloudWebhookPayload;
import com.cloudbees.jenkins.plugins.bitbucket.client.events.BitbucketCloudPushEvent;
import com.cloudbees.jenkins.plugins.bitbucket.endpoints.BitbucketCloudEndpoint;
import com.cloudbees.jenkins.plugins.bitbucket.server.client.BitbucketServerWebhookPayload;
import com.cloudbees.jenkins.plugins.bitbucket.server.events.BitbucketServerPushEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.scm.SCM;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import org.apache.commons.lang.StringUtils;

public class PushHookProcessor extends HookProcessor {

    private static final Logger LOGGER = Logger.getLogger(PushHookProcessor.class.getName());

    private static SCMEvent.Type getType(List<? extends BitbucketPushEvent.Change> changes) {
        SCMEvent.Type type = null;
        for (BitbucketPushEvent.Change change : changes) {
            if ((type == null || type == SCMEvent.Type.CREATED) && change.isCreated()) {
                type = SCMEvent.Type.CREATED;
            } else if ((type == null || type == SCMEvent.Type.REMOVED) && change.isClosed()) {
                type = SCMEvent.Type.REMOVED;
            } else {
                type = SCMEvent.Type.UPDATED;
            }
        }
        return type;
    }

    @Override
    public void process(HookEventType hookEvent, String payload, BitbucketType instanceType, String origin) {
        if (payload != null) {
            BitbucketPushEvent push;
            if (instanceType == BitbucketType.SERVER) {
                push = BitbucketServerWebhookPayload.pushEventFromPayload(payload);
            } else {
                push = BitbucketCloudWebhookPayload.pushEventFromPayload(payload);
            }
            if (push != null) {
                String owner = push.getRepository().getOwnerName();
                final String repository = push.getRepository().getRepositoryName();
                final List<? extends BitbucketPushEvent.Change> changes = push.getChanges();
                if (changes.isEmpty()) {
                    LOGGER.log(Level.INFO, "Received hook from Bitbucket without changes. Processing push event on {0}/{1}",
                        new Object[]{owner, repository});
                    scmSourceReIndex(owner, repository);
                } else {
                    final SCMEvent.Type type = getType(changes);
                    SCMHeadEvent.fireLater(new BitbucketPushEventSCMHeadEvent(type, push, origin), BitbucketSCMSource.getEventDelaySeconds(), TimeUnit.SECONDS);
                }
            }
        }
    }

    private static final class BitbucketPushEventSCMHeadEvent extends SCMHeadEvent<BitbucketPushEvent> implements HasBranches {
        public BitbucketPushEventSCMHeadEvent(Type type, BitbucketPushEvent push, String origin) {
            super(type, push, origin);
        }

        private static SCMHead getScmHead(String eventType, Reference change, Target target) {
            if ("tag".equals(eventType)) {
                // for BB Cloud date is valued only in case of annotated tag
                Date tagDate = change.getDate() != null ? change.getDate() : target.getDate();
                if (tagDate == null) {
                    // fall back to the jenkins time when the request is processed
                    tagDate = new Date();
                }
                return new BitbucketTagSCMHead(change.getName(), tagDate.getTime());
            } else {
                return new BranchSCMHead(change.getName());
            }
        }

        @Override
        public boolean isMatch(@NonNull SCMNavigator navigator) {
            if (!(navigator instanceof BitbucketSCMNavigator)) {
                return false;
            }
            BitbucketSCMNavigator bbNav = (BitbucketSCMNavigator) navigator;
            if (!isProjectKeyMatch(bbNav.getProjectKey())) {
                return false;
            }
            if (!isServerUrlMatch(bbNav.getServerUrl())) {
                return false;
            }
            return bbNav.getRepoOwner().equalsIgnoreCase(getPayload().getRepository().getOwnerName());
        }

        private boolean isProjectKeyMatch(String projectKey) {
            if (StringUtils.isBlank(projectKey)) {
                return true;
            }
            if (this.getPayload().getRepository().getProject() != null) {
                return projectKey.equals(this.getPayload().getRepository().getProject().getKey());
            }
            return true;
        }

        private boolean isServerUrlMatch(String serverUrl) {
            if (serverUrl == null || BitbucketCloudEndpoint.SERVER_URL.equals(serverUrl)) {
                // this is a Bitbucket cloud navigator
                return !(getPayload() instanceof BitbucketServerPushEvent);
            } else {
                // this is a Bitbucket server navigator
                if (getPayload() instanceof BitbucketCloudPushEvent) {
                    return false;
                }
                Map<String, List<BitbucketHref>> links = getPayload().getRepository().getLinks();
                if (links != null && links.containsKey("self")) {
                    boolean matches = false;
                    for (BitbucketHref link : links.get("self")) {
                        try {
                            URI navUri = new URI(serverUrl);
                            URI evtUri = new URI(link.getHref());
                            if (navUri.getHost().equalsIgnoreCase(evtUri.getHost())) {
                                matches = true;
                                break;
                            }
                        } catch (URISyntaxException e) {
                            // ignore
                        }
                    }
                    return matches;
                }
            }
            return true;
        }

        @NonNull
        @Override
        public String getSourceName() {
            return getPayload().getRepository().getRepositoryName();
        }

        private boolean scmSourceIsOriginOfEvent(@NonNull SCMSource source) {
            if (!(source instanceof BitbucketSCMSource)) {
                return false;
            }

            BitbucketSCMSource src = (BitbucketSCMSource) source;

            return isServerUrlMatch(src.getServerUrl()) &&
                src.getRepoOwner().equalsIgnoreCase(getPayload().getRepository().getOwnerName()) &&
                src.getRepository().equalsIgnoreCase(getPayload().getRepository().getRepositoryName());
        }

        @NonNull
        @Override
        public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
            if (!scmSourceIsOriginOfEvent(source)) {
                return Collections.emptyMap();
            }

            Map<SCMHead, SCMRevision> result = new HashMap<>();
            for (BitbucketPushEvent.Change change : getPayload().getChanges()) {
                if (change.isClosed()) {
                    result.put(new BranchSCMHead(change.getOld().getName()), null);
                } else {
                    // created is true
                    Reference newChange = change.getNew();
                    Target target = newChange.getTarget();

                    String eventType = newChange.getType();
                    SCMHead head = getScmHead(eventType, newChange, target);
                    result.put(head, new AbstractGitSCMSource.SCMRevisionImpl(head, target.getHash()));
                }
            }
            return result;
        }

        @Override
        public Iterable<BitbucketBranch> getBranches(BitbucketSCMSource src) {
            return getPayload().getBranches();
        }

        @Override
        public boolean isMatch(@NonNull SCM scm) {
            // TODO
            return false;
        }
    }

}
