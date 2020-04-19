#!/usr/bin/env bash

setup_git() {
  git config --global user.email "invalid@travis-ci.com"
  git config --global user.name "Travis-CI"
}

commit_website_files() {
  git add README.md
  git commit -m "Travis CI: readme update - $TRAVIS_BUILD_NUMBER"
}

upload_files() {
  git remote add origin https://${GH_TOKEN}@github.com/coursier/apps.git > /dev/null 2>&1
  git push origin master --quiet
}

setup_git
commit_website_files
upload_files
