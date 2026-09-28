# Automated release

LingFrame uses GitHub as its canonical release source. Pull requests run the full quality gate. Developers can manually dispatch the CI workflow with either the `full` or `performance` suite on a development branch. After a merge to `main`, `Main Smoke` runs a short post-merge verification; it does not repeat the full pull-request matrix.

When `Main Smoke` succeeds, the release workflow checks whether the merged commit contains an explicit stable version change and matching release notes. Only then does it create an immutable `V<version>` tag, a GitHub Release, and finally create or advance the `v<version>-release` stable branch. Ordinary feature merges do not create a release. The 0.4 line uses the codename “Hanzhang”, which is retained for 0.4.7.

To synchronize historical Releases, manually run `Release and Metadata Sync` on `main`. Enter one or more versions in `versions`, separated by commas or newlines (for example, `0.4.5,0.4.6,0.4.7`), and choose `all`, `gitee`, or `gitcode` in `platform`. The manual path reads the existing Releases from GitHub and copies their names, notes, and target commits to the selected platform. It does not recreate GitHub tags or Releases and does not require the checked-out source version to match a historical version. If the target platform already has a Release for the tag, creation is skipped.

Protect `main` by disallowing direct pushes and requiring the pull-request checks before merge. Required PR checks should include the SB2/SB3 build and test jobs, example integration smoke jobs, and their coverage gates. `Main Smoke` is a post-merge release preflight and does not replace the PR gate.

A release commit must update `lingframe-dependencies/pom.xml`, `lingframe-bom/pom.xml`, both changelogs, and add a matching `V<version>` changelog entry. The workflow rejects mismatched versions, non-stable semantic versions, or missing release notes.

The official Gitee and GitCode repository mirror settings synchronize branches and tags. After creating the GitHub Release, the workflow only synchronizes Release metadata through each platform's Release API. Configure these secrets in the `mirrors` GitHub Environment:

- `GITEE_TOKEN`
- `GITCODE_TOKEN`

The tokens need permission to create repository Releases. If a Release with the same tag already exists, the workflow skips creation. Maven Central remains a separate `-Prelease` operation; this workflow does not upload artifacts.

The stable release branch is created after the Release and does not need to be merged back into `main`. If metadata synchronization fails, rerun the workflow and select only the failed platform without recreating the GitHub Release.
