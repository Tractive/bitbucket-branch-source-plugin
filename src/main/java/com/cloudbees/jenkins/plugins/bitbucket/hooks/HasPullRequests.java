package com.cloudbees.jenkins.plugins.bitbucket.hooks;


import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketBranch;
import com.cloudbees.jenkins.plugins.bitbucket.api.BitbucketPullRequest;
import com.google.common.collect.Streams;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface HasPullRequests extends HasBranches {

    Iterable<BitbucketPullRequest> getPullRequests(BitbucketSCMSource src) throws InterruptedException;

    @Override
    default Iterable<BitbucketBranch> getBranches(BitbucketSCMSource src) throws InterruptedException {
        return Streams.stream(getPullRequests(src)).flatMap(
            bitbucketPullRequest -> Stream.of(
                bitbucketPullRequest.getSource().getBranch(),
                bitbucketPullRequest.getDestination().getBranch()
            )
        ).collect(Collectors.toList());
    }
}
