# Scabot helps out on GitHub, manages CI
Scabot (pronounced ska-BOH) helps shepherd pull requests in the [Scala repository](https://github.com/scala/scala) along the path from initial submission to final merge.

## Usage
Scabot tries to stay behind the scenes. It speaks to us through actions rather than words, where possible.
You shouldn't have to tell it what to do, unless something goes wrong (usually Jenkins acting up).

Scabot works by listening to webhook events from GitHub and [our CI server](https://scala-ci.typesafe.com).

It can be summoned (immediately!) through certain commands, posted as pull request comments (see below).

### Automations and Activities
  - Trigger CI builds for commits, keeping us informed of their progress. (It will also pick up the results of manual rebuilds done directly on the CI server.)
  - Set milestone of a PR based on its target branch.
  - Let us know whether a contributor has signed the Scala CLA.
  - Add reviewer request when there's a comment like "review by @authorityfigure"
    (obsolete feature, we use GitHub's built-in reviewing features instead now)
  - For its ambitions, check out [Scabot's issues](../../issues).

### Commands
  - `/rebuild`: rebuild failed jobs for all commits
  - `/rebuild $sha`: rebuild failed jobs for a given commit
  - `/sync`: make sure that commit stati are in sync with the actual builds on the [CI server](https://scala-ci.typesafe.com).
  - `/nothingtoseehere`: mark commits green without actually running CI on them

### PR Title Modifiers
  - `[ci: last-only]`: include anywhere in the PR title to avoid verifying all commits, limiting CI to the last one

## Admin
For ssh access to the server running the bot, this assumes you're using our [dev machine setup](https://github.com/scala/scala-jenkins-infra/blob/master/doc/client-setup.md).

### Deploy
Scabot runs on the CI server under the `scabot` account. We [push to deploy](../../issues/10). (The deployment process could maybe be redone at some point using sbt-native-packager's JavaServerAppPackaging (see https://github.com/sbt/sbt-native-packager/issues/521) -- or whatever Play folks usually use, now that Scabot is a Play/Akka app, not just Akka anymore.)

### Logs
`ssh jenkins-master`, and `tail -f ~scabot/log/application.log`

### Restart (last resort)
`ssh jenkins-master`, and `su scabot -c 'killall -1 java'`. The process will be restarted by [runit_service](https://docs.chef.io/inspec/resources/runit_service/)

## Command line interface (experimental)
The Scabot code includes a suite of methods corresponding to various
API calls, returning instances of case classes representing the
JSON responses from the API calls.  You can use these methods from the
Scala REPL to do your own queries. Here is a sample session.  Note
that for API calls that interact with GitHub, you must supply a
[personal GitHub API access token](https://help.github.com/articles/creating-an-access-token-for-command-line-use/)
when starting sbt.

```text
% sbt -Dscabot.github.token=... cli/console
Welcome to Scala ...
...

scala> val c = new scabot.cli.CLI("scala")
c: scabot.cli.CLI = ...

scala> c.await(c.lightbend.checkCla("som-snytt"))
res0: (c.CLARecord, spray.http.StatusCode) =
  (CLARecord(som-snytt,true,Some(1.0),1.0),200 OK)

scala> val pulls = c.await(c.github.pullRequests)
pulls: List[c.PullRequest] =
List(PullRequest(...), ...)

scala> pulls.filter(_.user == c.User("retronym")).map(_.number)
res0: List[Int] = List(4535, 4522)

scala> c.shutdown()
```

## Implementation notes
It's Akka-based.  ("My first Akka app", Adriaan says.)

It uses Spray to make web API calls.  (These days, maybe it should be using akka-http instead, if/when its SSL support is mature.)

Pull request statuses are updated using GitHub's [Status API](https://developer.github.com/v3/repos/statuses/).

We listen for webhook events, but just in case we missed some, a
`Synch` event triggers on startup and every 30 minutes thereafter
to make sure that our internal state is still in synch with reality.

In the SBT build, the root project aggregates five subprojects.
`server` depends on `amazon`, `github`, and `jenkins`; all of
them depend on `core`.  (TODO: just make `server` be the root project?)

The `amazon` subproject uses DynamoDB to persist a `scabot-seen-commands`
table, so at `Synch` time we don't reprocess stuff we already handled
once.

A good place to start exploring the code is
`server/src/main/scala/Actors.scala`.

PR status is updated in response to `PullRequestEvent` messages,
which might be real events coming directly from GitHub, or they might
synthetic ones we make when `Synch`ing.

## History
Old-timers may fondly remember Scabot's predecessor, the [Scala build kitteh](https://github.com/typesafehub/ghpullrequest-validator).

## Contributing
Yes, please!
