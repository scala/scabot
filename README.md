# Scabot helps out on GitHub, manages CI
Scabot listens to webhook events from GitHub and [our CI server](https://scala-ci.typesafe.com) to keep Github in synch with CI, among other things.

## Usage
Scabot tries to stay behind the scenes. It speaks to us through actions rather than words, where possible.
You shouldn't have to tell it what to do, unless something goes wrong (usually Jenkins acting up).

It can be summoned (immediately!) through certain commands, posted as pull request comments (see below).

### Automations and Activities
  - Trigger CI builds for commits, keeping us informed of their progress. (It will also pick up the results of manual rebuilds done directly on the CI server.)
  - Set milestone of a PR based on its target branch.
  - Add "reviewed" label when there's a comment starting with "LGTM"
  - For its ambitions, check out [Scabot's issues](../../issues).

### Commands
  - `/rebuild`: rebuild failed jobs for all commits
  - `/rebuild $sha`: rebuild failed jobs for a given commit
  - `/synch`: make sure that commit stati are in synch with the actual builds on the [CI server](https://scala-ci.typesafe.com).

## Admin
For ssh access to the server running the bot, this assumes you're using our [dev machine setup](https://github.com/scala/scala-jenkins-infra/blob/master/README.md#dev-machine-convenience).

### Deploy
Scabot runs on the CI server under the `scabot` account. We [push to deploy](../../issues/10). 

### Logs
`ssh scabot "less ~/logs/current"`

### Restart (last resort)
`ssh jenkins-master`, and `sudo /etc/init.d/scabot restart`.

## Contributing
Yes, please!
